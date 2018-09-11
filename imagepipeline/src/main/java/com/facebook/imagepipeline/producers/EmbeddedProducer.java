package com.facebook.imagepipeline.producers;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import com.facebook.common.internal.ImmutableMap;
import com.facebook.common.internal.VisibleForTesting;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.UriUtil;
import com.facebook.imagepipeline.bitmaps.SimpleBitmapReleaser;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.image.CloseableStaticBitmap;
import com.facebook.imagepipeline.image.ImmutableQualityInfo;
import com.facebook.imagepipeline.request.ImageRequest;
import java.util.Map;
import java.util.concurrent.Executor;

public class EmbeddedProducer implements Producer<CloseableReference<CloseableImage>> {

  public static final String PRODUCER_NAME = "AudioThumbnailProducer";
  @VisibleForTesting
  static final String CREATED_THUMBNAIL = "createdThumbnail";
  private final Executor mExecutor;
  private final ContentResolver mContentResolver;

  public EmbeddedProducer(Executor executor, ContentResolver contentResolver) {
    mExecutor = executor;
    mContentResolver = contentResolver;
  }

  @TargetApi(VERSION_CODES.GINGERBREAD_MR1)
  @Override
  public void produceResults(
      final Consumer<CloseableReference<CloseableImage>> consumer,
      final ProducerContext producerContext) {
    final ProducerListener listener = producerContext.getListener();
    final String requestId = producerContext.getId();
    final ImageRequest imageRequest = producerContext.getImageRequest();
    final StatefulProducerRunnable cancellableProducerRunnable =
        new StatefulProducerRunnable<CloseableReference<CloseableImage>>(
            consumer,
            listener,
            PRODUCER_NAME,
            requestId) {
          @Override
          protected void onSuccess(CloseableReference<CloseableImage> result) {
            super.onSuccess(result);
            listener.onUltimateProducerReached(requestId, PRODUCER_NAME, result != null);
          }

          @Override
          protected void onFailure(Exception e) {
            super.onFailure(e);
            listener.onUltimateProducerReached(requestId, PRODUCER_NAME, false);
          }


          @Override
          protected CloseableReference<CloseableImage> getResult() throws Exception {
            String path = getLocalFilePath(imageRequest);
            if (path == null) {
              return null;
            }
            byte[] bitmapData;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
              retriever.setDataSource(path);
              bitmapData = retriever.getEmbeddedPicture();
            } finally {
              retriever.release();
            }
            if (bitmapData == null) {
              return null;
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.length);
            if (bitmap == null) {
              return null;
            }
            return CloseableReference.<CloseableImage>of(
                new CloseableStaticBitmap(
                    bitmap,
                    SimpleBitmapReleaser.getInstance(),
                    ImmutableQualityInfo.FULL_QUALITY,
                    0));
          }

          @Override
          protected Map<String, String> getExtraMapOnSuccess(
              final CloseableReference<CloseableImage> result) {
            return ImmutableMap.of(CREATED_THUMBNAIL, String.valueOf(result != null));
          }

          @Override
          protected void disposeResult(CloseableReference<CloseableImage> result) {
            CloseableReference.closeSafely(result);
          }
        };
    producerContext.addCallbacks(
        new BaseProducerContextCallbacks() {
          @Override
          public void onCancellationRequested() {
            cancellableProducerRunnable.cancel();
          }
        });
    mExecutor.execute(cancellableProducerRunnable);
  }

  @Nullable
  private String getLocalFilePath(ImageRequest imageRequest) {
    final Uri uri = imageRequest.getSourceUri();
    return uri != null ? uri.getPath() : null;
//    if (UriUtil.isLocalFileUri(uri)) {
//      return imageRequest.getSourceFile().getPath();
//    } else if (UriUtil.isLocalContentUri(uri)) {
//      String selection = null;
//      String[] selectionArgs = null;
//      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
//          && "com.android.providers.media.documents".equals(uri.getAuthority())) {
//        String documentId = DocumentsContract.getDocumentId(uri);
//        uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
//        selection = MediaStore.Audio.Media._ID + "=?";
//        selectionArgs = new String[]{documentId.split(":")[1]};
//      }
//      Cursor cursor =
//          mContentResolver.query(
//              uri, new String[]{MediaStore.Audio.Media.DATA}, selection, selectionArgs, null);
//      try {
//        if (cursor != null && cursor.moveToFirst()) {
//          return cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
//        }
//      } finally {
//        if (cursor != null) {
//          cursor.close();
//        }
//      }
//    }
//    return null;
  }
}
