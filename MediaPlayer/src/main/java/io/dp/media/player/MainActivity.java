/*
 * Copyright (C) 2013 Dmitry Polishuk dmitry.polishuk@gmail.com
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.dp.media.player;

import android.os.Bundle;
import android.app.Activity;
import android.view.Menu;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
  private Player player = null;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.acitivity_main);

    SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surface);
    surfaceView.getHolder().addCallback(this);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    if (player == null) {
      try {
        player = new Player(holder.getSurface());
        player.start();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    if (player != null) {
      player.stop();
    }
  }
}
