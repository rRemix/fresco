/*
 * This file provided by Facebook is for non-commercial testing and evaluation
 * purposes only.  Facebook reserves all rights not expressly granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * FACEBOOK BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.facebook.fresco.samples.showcase.drawee;

import android.graphics.drawable.Animatable;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.controller.ControllerListener;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.fresco.samples.showcase.BaseShowcaseFragment;
import com.facebook.fresco.samples.showcase.R;
import com.facebook.fresco.samples.showcase.misc.ImageUriProvider;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.imagepipeline.image.QualityInfo;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Simple drawee fragment that just displays an image.
 */
public class DraweeSimpleFragment extends BaseShowcaseFragment {

  @Nullable
  @Override
  public View onCreateView(
      LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_drawee_simple, container, false);
  }

  @Override
  public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
    final ImageUriProvider imageUriProvider = ImageUriProvider.getInstance(getContext());
    final Uri uri = imageUriProvider.createSampleUri(ImageUriProvider.ImageSize.M);

//    final String uriString = "file:///storage/emulated/0/不才 - 藏龙.png";
    final String uriString = "embedded:///storage/emulated/0/Crash/不才 - 藏龙.mp3";
    ImageRequestBuilder imageRequestBuilder = ImageRequestBuilder.newBuilderWithSource(Uri.parse(uriString));
    imageRequestBuilder.setResizeOptions(
          ResizeOptions.forDimensions(200,300));


    SimpleDraweeView simpleDraweeView = view.findViewById(R.id.drawee_view);
    DraweeController controller = Fresco.newDraweeControllerBuilder()
        .setImageRequest(imageRequestBuilder.build())
        .setOldController(simpleDraweeView.getController())
        .setControllerListener(new ControllerListener<ImageInfo>() {
          @Override
          public void onSubmit(String id, Object callerContext) {
          }

          @Override
          public void onFinalImageSet(String id, @javax.annotation.Nullable ImageInfo imageInfo, @javax.annotation.Nullable Animatable animatable) {
            if(imageInfo != null){
              int width = imageInfo.getWidth();
              int height = imageInfo.getHeight();
              QualityInfo qualityInfo = imageInfo.getQualityInfo();
            }
          }

          @Override
          public void onIntermediateImageSet(String id,
              @javax.annotation.Nullable ImageInfo imageInfo) {

          }

          @Override
          public void onIntermediateImageFailed(String id, Throwable throwable) {

          }

          @Override
          public void onFailure(String id, Throwable throwable) {

          }

          @Override
          public void onRelease(String id) {

          }
        })
        .build();
    simpleDraweeView.setController(controller);
  }

  @Override
  public int getTitleId() {
    return R.string.drawee_simple_title;
  }
}
