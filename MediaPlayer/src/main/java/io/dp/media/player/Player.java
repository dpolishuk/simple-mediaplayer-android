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

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by dp on 9/15/13.
 */
public class Player {

  private MediaExtractor extractor;
  private MediaCodec videoDecoder;
  private MediaCodec audioDecoder;
  private Surface surface;

  private AudioTrack audioTrack = null;

  private int audioIdx = -1, videoIdx = -1;

  private Thread videoThread = null;

  private static final
  String
      SAMPLE =
      "http://dl.dropboxusercontent.com/s/os005gbt1kj4mmw/t1_720.mp4";

  public Player(Surface surface) throws IOException {
    this.surface = surface;

    extractor = new MediaExtractor();
    extractor.setDataSource(SAMPLE);

    for (int i = 0; i < extractor.getTrackCount(); i++) {
      MediaFormat format = extractor.getTrackFormat(i);
      String mime = format.getString(MediaFormat.KEY_MIME);
      if (mime.startsWith("video/")) {
        videoIdx = i;
        extractor.selectTrack(i);
        videoDecoder = MediaCodec.createDecoderByType(mime);
        videoDecoder.configure(format, surface, null, 0);
        continue;
      }

      if (mime.startsWith("audio/")) {
        audioIdx = i;
        extractor.selectTrack(i);
        audioDecoder = MediaCodec.createDecoderByType(mime);
        audioDecoder.configure(format, null, null, 0);
        continue;
      }
    }

    if (null == videoDecoder) {
      throw new IOException("Cannot find video decoder");
    }

    if (null == audioDecoder) {
      throw new IOException("Cannot find audio decoder");
    }
  }

  public void start() {
    videoThread = new VideoThread();
    videoThread.start();
//    new AudioThread().start();
  }

  public void stop() {
    if (null != videoThread) {
      videoThread.interrupt();
    }
  }

  private double getAudioPtsMs() {
    if (audioTrack != null) {
      return (audioTrack.getPlaybackHeadPosition() / audioTrack.getSampleRate()) * 1000.0;
    }

    return 0;
  }

  private void processVideo(ByteBuffer[] videoInputBuffers, ByteBuffer[] videoOutputBuffers) {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    if (!isEOS) {
      int inIndex = videoDecoder.dequeueInputBuffer(10000);

      if (inIndex >= 0) {
        ByteBuffer buffer = videoInputBuffers[inIndex];
        int videoSampleSize = extractor.readSampleData(buffer, 0);
        if (videoSampleSize < 0) {
          Log.d(Const.TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM");
          videoDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
          isEOS = true;
        } else {
          videoDecoder.queueInputBuffer(inIndex, 0, videoSampleSize, extractor.getSampleTime(), 0);
          extractor.advance();
        }
      }
    }

    int outIndex = videoDecoder.dequeueOutputBuffer(info, 10000);
    switch (outIndex) {
      case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
        Log.d(Const.TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
        videoOutputBuffers = videoDecoder.getOutputBuffers();
        break;
      case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
        Log.d(Const.TAG, "New format " + videoDecoder.getOutputFormat());
        break;
      case MediaCodec.INFO_TRY_AGAIN_LATER:
        Log.d(Const.TAG, "dequeueOutputBuffer timed out!");
        break;
      default:
        ByteBuffer buffer = videoOutputBuffers[outIndex];

        while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
          try {
//            Log.d(Const.TAG, "Wait video");
            Thread.sleep(10);
          } catch (InterruptedException e) {
            e.printStackTrace();
            break;
          }
        }

        if ((info.presentationTimeUs / 1000.0 - getAudioPtsMs()) < -40.0) {
          Log.d(Const.TAG, "SKIP Video frame");
          videoDecoder.releaseOutputBuffer(outIndex, false);
        } else {
          videoDecoder.releaseOutputBuffer(outIndex, true);
        }

        break;
    }

    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
      Log.d(Const.TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
      return;
    }
  }

  private void processAudio(ByteBuffer[] audioInputBuffers, ByteBuffer[] audioOutputBuffers) {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    if (!isEOS) {
      int inIndex = audioDecoder.dequeueInputBuffer(10000);

      synchronized (extractor) {
        if (inIndex >= 0) {
//          extractor.selectTrack(audioIdx);
          ByteBuffer buffer = audioInputBuffers[inIndex];
          int audioSampleSize = extractor.readSampleData(buffer, 0);
          if (audioSampleSize < 0) {
            Log.d(Const.TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM");
            audioDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            isEOS = true;
          } else {
            audioDecoder
                .queueInputBuffer(inIndex, 0, audioSampleSize, extractor.getSampleTime(), 0);
            extractor.advance();
          }
        }
      }
    }

    int outIndex = audioDecoder.dequeueOutputBuffer(info, 10000);
    switch (outIndex) {
      case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
        Log.d(Const.TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
        audioOutputBuffers = audioDecoder.getOutputBuffers();
        break;
      case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
        MediaFormat format = audioDecoder.getOutputFormat();
        Log.d(Const.TAG, "New format " + format);

        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        int
            channelConfig =
            channelCount == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;

        int
            bufSize =
            AudioTrack
                .getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        this.audioTrack =
            new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                           AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);

        audioTrack.play();
        break;

      case MediaCodec.INFO_TRY_AGAIN_LATER:
        Log.d(Const.TAG, "dequeueOutputBuffer timed out!");
        break;

      default:
        ByteBuffer buffer = audioOutputBuffers[outIndex];
        Log.d(Const.TAG, "Got audio buffer");

        audioPtsUs = info.presentationTimeUs;

        byte[] pcm = new byte[info.size];
        buffer.get(pcm);
        buffer.clear();

        if (pcm.length > 0 && audioTrack != null) {
          audioTrack.write(pcm, 0, pcm.length);
        }

        audioDecoder.releaseOutputBuffer(outIndex, false);
        break;
    }

    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
      Log.d(Const.TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
      return;
    }
  }

  private long startMs = -1;
  private boolean isEOS = false;
  private long audioPtsUs = -1;

  private class VideoThread extends Thread {

    @Override
    public void run() {
      videoDecoder.start();
      audioDecoder.start();

      ByteBuffer[] videoInputBuffers = videoDecoder.getInputBuffers();
      ByteBuffer[] videoOutputBuffers = videoDecoder.getOutputBuffers();

      ByteBuffer[] audioInputBuffers = audioDecoder.getInputBuffers();
      ByteBuffer[] audioOutputBuffers = audioDecoder.getOutputBuffers();

      isEOS = false;
      startMs = System.currentTimeMillis();

      while (!Thread.interrupted()) {
        if (extractor.getSampleTrackIndex() == audioIdx) {
          processAudio(audioInputBuffers, audioOutputBuffers);
        } else if (extractor.getSampleTrackIndex() == videoIdx) {
          processVideo(videoInputBuffers, videoOutputBuffers);
        }
      }

      videoDecoder.stop();
      videoDecoder.release();

      audioDecoder.stop();
      audioDecoder.release();
      extractor.release();
    }
  }
}

