package com.facebook.imagepipeline.producers;

import android.annotation.TargetApi;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build.VERSION_CODES;
import android.support.annotation.Nullable;
import com.facebook.common.memory.PooledByteBufferFactory;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.request.ImageRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.Executor;

public class EmbeddedCoverProducer extends LocalFetchProducer {

  public static final String PRODUCER_NAME = "EmbeddedCoverProducer";

  public EmbeddedCoverProducer(Executor executor,
      PooledByteBufferFactory pooledByteBufferFactory) {
    super(executor, pooledByteBufferFactory);
  }

  @TargetApi(VERSION_CODES.GINGERBREAD_MR1)
  @Override
  protected EncodedImage getEncodedImage(ImageRequest imageRequest) throws IOException {
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
    return getEncodedImage(
        new ByteArrayInputStream(bitmapData),
        bitmapData.length);
  }

  @Override
  protected String getProducerName() {
    return PRODUCER_NAME;
  }

  @Nullable
  private String getLocalFilePath(ImageRequest imageRequest) {
    final Uri uri = imageRequest.getSourceUri();
    return uri != null ? uri.getPath() : null;
  }
}
