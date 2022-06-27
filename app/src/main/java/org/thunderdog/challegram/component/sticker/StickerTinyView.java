/*
 * This file is a part of Telegram X
 * Copyright © 2014-2022 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 22/11/2016
 */
package org.thunderdog.challegram.component.sticker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.gif.GifFile;
import org.thunderdog.challegram.loader.gif.GifReceiver;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;

import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.lambda.CancellableRunnable;
import me.vkryl.core.lambda.Destroyable;

public class StickerTinyView extends View implements FactorAnimator.Target, Destroyable {

  private static final float MIN_SCALE = 1.3f;
  private static final long CLICK_LIFESPAN = 230l;
  private static final long LONG_PRESS_DELAY = 1000;

  public static final float PADDING = 8f;
  private static final Interpolator OVERSHOOT_INTERPOLATOR = new OvershootInterpolator(3.2f);

  private final ImageReceiver imageReceiver;
  private final GifReceiver gifReceiver;
  private final FactorAnimator animator;
  private @Nullable TGStickerObj sticker;
  private Path contour;

  private boolean isAnimation;

  private CancellableRunnable longPress;

  private boolean isPressed;
  private boolean longPressScheduled;
  private boolean longPressReady;

  private OnTouchCallback callback;

  public StickerTinyView (Context context) {
    super(context);
    this.imageReceiver = new ImageReceiver(this, 0);
    this.gifReceiver = new GifReceiver(this);
    this.animator = new FactorAnimator(0, this, OVERSHOOT_INTERPOLATOR, CLICK_LIFESPAN);
  }

  public void setSticker (@Nullable TGStickerObj sticker) {
    this.sticker = sticker;
    this.isAnimation = sticker != null && sticker.isAnimated();
    resetStickerState();
    ImageFile imageFile = sticker != null && !sticker.isEmpty() ? sticker.getImage() : null;
    GifFile gifFile = sticker != null && !sticker.isEmpty() ? sticker.getPreviewAnimation() : null;
    if ((sticker == null || sticker.isEmpty()) && imageFile != null) {
      throw new RuntimeException("");
    }
    contour = sticker != null ? sticker.getContour(Math.min(imageReceiver.getWidth(), imageReceiver.getHeight())) : null;
    imageReceiver.requestFile(imageFile);
    if (gifFile != null)
      gifFile.setPlayOnce(true);
    gifReceiver.requestFile(gifFile);
  }

  public void attach () {
    imageReceiver.attach();
    gifReceiver.attach();
  }

  public void detach () {
    imageReceiver.detach();
    gifReceiver.detach();
  }

  @Override
  public void performDestroy () {
    imageReceiver.destroy();
    gifReceiver.destroy();
  }

  private float factor;

  private void resetStickerState () {
    animator.forceFactor(0f, true);
    factor = 0f;
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    if (this.factor != factor) {
      this.factor = factor;
      invalidate();
    }
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    int padding = Screen.dp(PADDING);
    int width = getMeasuredWidth();
    int height = getMeasuredHeight();
    imageReceiver.setBounds(padding, padding + getPaddingTop(), width - padding, height - getPaddingBottom() - padding);
    gifReceiver.setBounds(padding, padding + getPaddingTop(), width - padding, height - getPaddingBottom() - padding);
    contour = sticker != null ? sticker.getContour(Math.min(imageReceiver.getWidth(), imageReceiver.getHeight())) : null;
  }

  @Override
  protected void onDraw (Canvas c) {
    boolean saved = factor != 0f;
    if (saved) {
      c.save();
      float scale = MIN_SCALE + (1f - MIN_SCALE) * (1f - factor);
      int cx = getMeasuredWidth() / 2;
      int cy = getPaddingTop() + (getMeasuredHeight() - getPaddingBottom() - getPaddingBottom()) / 2;
      c.scale(scale, scale, cx, cy);
    }
    if (isAnimation) {
      if (gifReceiver.needPlaceholder()) {
        if (imageReceiver.needPlaceholder()) {
          imageReceiver.drawPlaceholderContour(c, contour);
        }
        imageReceiver.draw(c);
      }
      gifReceiver.draw(c);
    } else {
      if (imageReceiver.needPlaceholder()) {
        imageReceiver.drawPlaceholderContour(c, contour);
      }
      imageReceiver.draw(c);
    }
    if (saved) {
      c.restore();
    }
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        startTouch();
        return true;
      }
      case MotionEvent.ACTION_CANCEL: {
        cancelTouch();
        return true;
      }
      case MotionEvent.ACTION_UP: {
        boolean isSingleClicked = longPressScheduled && !longPressReady;
        boolean isLongPressed = !longPressScheduled && longPressReady;
        cancelTouch();
        if (isSingleClicked) {
          onSingleTapped();
        }
        if (isLongPressed) {
          onLongReleased();
        }
        return true;
      }
    }
    return true;
  }

  private void startTouch () {
    setStickerPressed(true);
    openPreviewDelayed();
  }

  private void cancelTouch () {
    setStickerPressed(false);
    cancelDelayedPreview();
    longPressReady = false;
  }

  private void setStickerPressed (boolean isPressed) {
    if (this.isPressed != isPressed) {
      this.isPressed = isPressed;
      animator.animateTo(isPressed ? 1f : 0f);
    }
  }

  private void openPreviewDelayed () {
    cancelDelayedPreview();
    longPress = new CancellableRunnable() {
      @Override
      public void act () {
        onLongPressed();
        longPressScheduled = false;
      }
    };
    longPressScheduled = true;
    postDelayed(longPress, LONG_PRESS_DELAY);
  }
  private void cancelDelayedPreview () {
    if (longPress != null) {
      longPress.cancel();
      longPress = null;
    }
    longPressScheduled = false;
  }

  private void onSingleTapped () {
    android.util.Log.d("AKBOLAT", "onSingleTapped");
    if (callback != null) {
      callback.onSingleTap();
    }
  }

  private void onLongPressed () {
    android.util.Log.d("AKBOLAT", "onLongPressed");
    longPressReady = true;

    UI.forceVibrate(this, true);
  }

  private void onLongReleased () {
    android.util.Log.d("AKBOLAT", "onLongReleased");
    if (callback != null) {
      callback.onLongRelease();
    }
  }

  public void setCallback (OnTouchCallback callback) {
    this.callback = callback;
  }

  public interface OnTouchCallback {
    void onSingleTap ();
    void onLongRelease();
  }
}