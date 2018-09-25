/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.imagepipeline.producers;

import static com.facebook.imagepipeline.transcoder.JpegTranscoderUtils.DEFAULT_JPEG_QUALITY;
import static com.facebook.imagepipeline.transcoder.JpegTranscoderUtils.INVERTED_EXIF_ORIENTATIONS;

import android.media.ExifInterface;
import com.facebook.common.internal.ImmutableMap;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.VisibleForTesting;
import com.facebook.common.memory.PooledByteBuffer;
import com.facebook.common.memory.PooledByteBufferFactory;
import com.facebook.common.memory.PooledByteBufferOutputStream;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.TriState;
import com.facebook.imageformat.DefaultImageFormats;
import com.facebook.imageformat.ImageFormat;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.common.RotationOptions;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.transcoder.ImageTranscodeResult;
import com.facebook.imagepipeline.transcoder.ImageTranscoder;
import com.facebook.imagepipeline.transcoder.JpegTranscoderUtils;
import com.facebook.imagepipeline.transcoder.TranscodeStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * Resizes and rotates JPEG image according to the EXIF orientation data or a specified rotation
 * angle.
 *
 * <p> If the image is not JPEG, no transformation is applied.
 *
 * <p> This can be used even if downsampling is enabled as long as resizing is disabled in the
 * constructor.
 */
public class ResizeAndRotateProducer implements Producer<EncodedImage> {
  private static final String PRODUCER_NAME = "ResizeAndRotateProducer";
  private static final String ORIGINAL_SIZE_KEY = "Original size";
  private static final String REQUESTED_SIZE_KEY = "Requested size";
  private static final String TRANSCODING_RESULT = "Transcoding result";
  private static final String TRANSCODER_ID = "Transcoder id";

  @VisibleForTesting static final int MIN_TRANSFORM_INTERVAL_MS = 100;

  private final Executor mExecutor;
  private final PooledByteBufferFactory mPooledByteBufferFactory;
  private final Producer<EncodedImage> mInputProducer;
  private final ImageTranscoder mImageTranscoder;

  public ResizeAndRotateProducer(
      final Executor executor,
      final PooledByteBufferFactory pooledByteBufferFactory,
      final Producer<EncodedImage> inputProducer,
      final ImageTranscoder imageTranscoder) {
    mExecutor = Preconditions.checkNotNull(executor);
    mPooledByteBufferFactory = Preconditions.checkNotNull(pooledByteBufferFactory);
    mInputProducer = Preconditions.checkNotNull(inputProducer);
    mImageTranscoder = Preconditions.checkNotNull(imageTranscoder);
  }

  @Override
  public void produceResults(
      final Consumer<EncodedImage> consumer,
      final ProducerContext context) {
    mInputProducer.produceResults(
        new TransformingConsumer(consumer, context, mImageTranscoder), context);
  }

  private class TransformingConsumer extends DelegatingConsumer<EncodedImage, EncodedImage> {

    private final ImageTranscoder mImageTranscoder;
    private final ProducerContext mProducerContext;
    private boolean mIsCancelled;

    private final JobScheduler mJobScheduler;

    public TransformingConsumer(
        final Consumer<EncodedImage> consumer,
        final ProducerContext producerContext,
        final ImageTranscoder imageTranscoder) {
      super(consumer);
      mIsCancelled = false;
      mProducerContext = producerContext;
      mImageTranscoder = imageTranscoder;

      JobScheduler.JobRunnable job = new JobScheduler.JobRunnable() {
        @Override
        public void run(EncodedImage encodedImage, @Status int status) {
          doTransform(encodedImage, status);
        }
      };
      mJobScheduler = new JobScheduler(mExecutor, job, MIN_TRANSFORM_INTERVAL_MS);

      mProducerContext.addCallbacks(
          new BaseProducerContextCallbacks() {
            @Override
            public void onIsIntermediateResultExpectedChanged() {
              if (mProducerContext.isIntermediateResultExpected()) {
                mJobScheduler.scheduleJob();
              }
            }

            @Override
            public void onCancellationRequested() {
              mJobScheduler.clearJob();
              mIsCancelled = true;
              // this only works if it is safe to discard the output of previous producer
              consumer.onCancellation();
            }
          });
    }

    @Override
    protected void onNewResultImpl(@Nullable EncodedImage newResult, @Status int status) {
      if (mIsCancelled) {
        return;
      }
      boolean isLast = isLast(status);
      if (newResult == null) {
        if (isLast) {
          getConsumer().onNewResult(null, Consumer.IS_LAST);
        }
        return;
      }
      TriState shouldTransform =
          shouldTransform(mProducerContext.getImageRequest(), newResult, mImageTranscoder);
      // ignore the intermediate result if we don't know what to do with it
      if (!isLast && shouldTransform == TriState.UNSET) {
        return;
      }
      // just forward the result if we know that it shouldn't be transformed
      if (shouldTransform != TriState.YES) {
        if (!mProducerContext.getImageRequest().getRotationOptions().canDeferUntilRendered() &&
            newResult.getRotationAngle() != 0 &&
            newResult.getRotationAngle() != EncodedImage.UNKNOWN_ROTATION_ANGLE) {
          newResult = moveImage(newResult); // for thread-safety sake
          newResult.setRotationAngle(0);
        }
        getConsumer().onNewResult(newResult, status);
        return;
      }
      // we know that the result should be transformed, hence schedule it
      if (!mJobScheduler.updateJob(newResult, status)) {
        return;
      }
      if (isLast || mProducerContext.isIntermediateResultExpected()) {
        mJobScheduler.scheduleJob();
      }
    }

    private EncodedImage moveImage(EncodedImage newResult) {
      EncodedImage cloned = EncodedImage.cloneOrNull(newResult);
      newResult.close();
      return cloned;
    }

    private void doTransform(EncodedImage encodedImage, @Status int status) {
      mProducerContext.getListener().onProducerStart(mProducerContext.getId(), PRODUCER_NAME);
      ImageRequest imageRequest = mProducerContext.getImageRequest();
      PooledByteBufferOutputStream outputStream = mPooledByteBufferFactory.newOutputStream();
      Map<String, String> extraMap = null;
      EncodedImage ret;
      try {
        ImageTranscodeResult result =
            mImageTranscoder.transcode(
                encodedImage,
                outputStream,
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                null,
                DEFAULT_JPEG_QUALITY);
        extraMap =
            getExtraMap(
                encodedImage,
                imageRequest.getResizeOptions(),
                result,
                mImageTranscoder.getIdentifier());
        CloseableReference<PooledByteBuffer> ref =
            CloseableReference.of(outputStream.toByteBuffer());
        try {
          ret = new EncodedImage(ref);
          ret.setImageFormat(DefaultImageFormats.JPEG);
          try {
            ret.parseMetaData();
            mProducerContext.getListener().
                onProducerFinishWithSuccess(mProducerContext.getId(), PRODUCER_NAME, extraMap);
            if (result.getTranscodeStatus() != TranscodeStatus.TRANSCODING_NO_RESIZING) {
              status |= Consumer.IS_RESIZING_DONE;
            }
            getConsumer().onNewResult(ret, status);
          } finally {
            EncodedImage.closeSafely(ret);
          }
        } finally {
          CloseableReference.closeSafely(ref);
        }
      } catch (Exception e) {
        mProducerContext.getListener().
            onProducerFinishWithFailure(mProducerContext.getId(), PRODUCER_NAME, e, extraMap);
        if (isLast(status)) {
          getConsumer().onFailure(e);
        }
        return;
      } finally {
        outputStream.close();
      }
    }

    private Map<String, String> getExtraMap(
        EncodedImage encodedImage,
        @Nullable ResizeOptions resizeOptions,
        @Nullable ImageTranscodeResult transcodeResult,
        @Nullable String transcoderId) {
      if (!mProducerContext.getListener().requiresExtraMap(mProducerContext.getId())) {
        return null;
      }
      String originalSize = encodedImage.getWidth() + "x" + encodedImage.getHeight();

      String requestedSize;
      if (resizeOptions != null) {
        requestedSize = resizeOptions.width + "x" + resizeOptions.height;
      } else {
        requestedSize = "Unspecified";
      }

      final Map<String, String> map = new HashMap<>();
      map.put(ORIGINAL_SIZE_KEY, originalSize);
      map.put(REQUESTED_SIZE_KEY, requestedSize);
      map.put(JobScheduler.QUEUE_TIME_KEY, String.valueOf(mJobScheduler.getQueuedTime()));
      map.put(TRANSCODER_ID, transcoderId);
      map.put(TRANSCODING_RESULT, String.valueOf(transcodeResult));
      return ImmutableMap.copyOf(map);
    }
  }

  private static TriState shouldTransform(
      ImageRequest request,
      EncodedImage encodedImage,
      ImageTranscoder imageTranscoder) {
    if (encodedImage == null || encodedImage.getImageFormat() == ImageFormat.UNKNOWN) {
      return TriState.UNSET;
    }
    if (encodedImage.getImageFormat() != DefaultImageFormats.JPEG) {
      return TriState.NO;
    }
    return TriState.valueOf(
        shouldRotate(request.getRotationOptions(), encodedImage)
            || imageTranscoder.canResize(
                encodedImage, request.getRotationOptions(), request.getResizeOptions()));
  }

  private static boolean shouldRotate(RotationOptions rotationOptions, EncodedImage encodedImage) {
    return !rotationOptions.canDeferUntilRendered()
        && (JpegTranscoderUtils.getRotationAngle(rotationOptions, encodedImage) != 0
            || shouldRotateUsingExifOrientation(rotationOptions, encodedImage));
  }

  private static boolean shouldRotateUsingExifOrientation(
      RotationOptions rotationOptions, EncodedImage encodedImage) {
    if (!rotationOptions.rotationEnabled() || rotationOptions.canDeferUntilRendered()) {
      encodedImage.setExifOrientation(ExifInterface.ORIENTATION_UNDEFINED);
      return false;
    }
    return INVERTED_EXIF_ORIENTATIONS.contains(encodedImage.getExifOrientation());
  }
}
