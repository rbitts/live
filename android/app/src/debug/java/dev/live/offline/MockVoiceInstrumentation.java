package dev.live.offline;

import android.app.Activity;
import android.content.Intent;
import android.media.*;
import android.os.*;
import java.io.*;
import org.json.JSONObject;

/** Real offline TTS -> paced capture/VAD -> actual ASR/VLM and playback. No text submit. */
public final class MockVoiceInstrumentation extends VoiceConversationInstrumentation {
  private void send(String text) {
    runOnMainSync(() -> { try {
      require((boolean)call("sendMockVoice", new Class<?>[]{String.class}, text), "mock request refused");
    } catch(Exception e) { throw new RuntimeException(e); }});
  }
  private long awaitOnset(long previous) throws Exception {
    long deadline=SystemClock.elapsedRealtime()+15000;
    while(epoch()==previous && SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
    require(epoch()!=previous, "mock PCM did not trigger production VAD");
    return epoch();
  }
  private long awaitMockAudio() throws Exception {
    long deadline=SystemClock.elapsedRealtime()+60000;
    StreamingAsr asr=(StreamingAsr)get("streamingAsr");
    while(SystemClock.elapsedRealtime()<deadline) {
      require((boolean)get("foreground"), "app left foreground during mock conversation");
      long current=epoch();
      if((long)get("finalizedAsrTurn")==asr.current() && !(boolean)get("userSpeaking")
          && (long)get("speakingEpoch")==current && (long)get("firstPlaybackMs")>=0) {
        turns.put(new JSONObject().put("epoch",current).put("first_text_ms",get("firstTextMs"))
            .put("first_audio_write_ms",get("firstPlaybackMs")).put("response",get("response")));
        return current;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("mock input produced no committed answer audio");
  }
  public void onStart() {
    Bundle result=new Bundle();
    try {
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long deadline=SystemClock.elapsedRealtime()+90000;
      while((!(boolean)get("loaded") || (boolean)get("modelBusy") || !(boolean)get("foreground"))
          && SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
      require((boolean)get("loaded") && !(boolean)get("modelBusy") && (boolean)get("foreground"),"load/foreground");
      require(!(boolean)get("cameraEnabled"),"camera off");
      MockVoiceInput synth=(MockVoiceInput)get("mockVoice");
      deadline=SystemClock.elapsedRealtime()+10000;
      while(!synth.ready() && SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
      require(synth.ready(),"offline Korean input voice unavailable");
      AudioManager audio=(AudioManager)activity.getSystemService(android.content.Context.AUDIO_SERVICE);
      int modeBefore=audio.getMode();
      runOnMainSync(()->{try{call("startCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      Capture capture=(Capture)get("capture");require(capture!=null,"capture");
      require(audio.getMode()==modeBefore,"app changed system audio mode");
      java.lang.reflect.Field recorderField=Capture.class.getDeclaredField("recorder");recorderField.setAccessible(true);
      AudioRecord recorder=(AudioRecord)recorderField.get(capture);
      require(recorder.getAudioSource()==MediaRecorder.AudioSource.DEFAULT,"non-default input source");
      require(recorder.getPreferredDevice()==null,"app forced input device");
      AudioTrack output=(AudioTrack)get("track");
      int outputUsage=-1;
      if(Build.VERSION.SDK_INT>=29) {
        outputUsage=output.getAudioAttributes().getUsage();
        require(outputUsage==AudioAttributes.USAGE_MEDIA,"non-media output");
      }
      require(output.getPreferredDevice()==null,"app forced output device");
      report.put("system_audio_mode",modeBefore).put("input_source",recorder.getAudioSource())
          .put("output_usage",outputUsage);
      // Enable the source separately so the first observed epoch change is VAD onset.
      runOnMainSync(()->capture.mockInput.enable());Thread.sleep(300);
      long before=epoch(),at=SystemClock.elapsedRealtime();
      send("오이 샐러드 만드는 방법을 자세히 알려 줘.");
      awaitOnset(before);
      report.put("first_mock_onset_ms",SystemClock.elapsedRealtime()-at);
      long first=awaitMockAudio();
      report.put("actual_input_type",recorder.getRoutedDevice()==null?-1:recorder.getRoutedDevice().getType())
          .put("actual_output_type",output.getRoutedDevice()==null?-1:output.getRoutedDevice().getType());
      report.put("first_caption",get("microphoneTranscript")).put("turns",turns);
      require(!((String)get("microphoneTranscript")).isBlank(),"empty real ASR");
      long captureBefore=capture.capturedSamples.get();
      deadline=SystemClock.elapsedRealtime()+5000;
      while(!(boolean)call("isVoicePlaying",new Class<?>[]{}) && SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
      require((boolean)call("isVoicePlaying",new Class<?>[]{}),"no actual playback to interrupt");
      at=SystemClock.elapsedRealtime();send("잠깐만. 하나 더하기 하나는 얼마야?");
      awaitOnset(first);
      report.put("mock_send_to_barge_in_ms",SystemClock.elapsedRealtime()-at);
      require(!activity.onPcm(first,new float[480]),"late PCM accepted");
      long second=awaitMockAudio();awaitDrain(second);
      report.put("second_caption",get("microphoneTranscript")).put("final_response",get("response"));
      require(capture.capturedSamples.get()>captureBefore && (boolean)get("listening"),"capture blocked");
      long delivered=capture.mockInput.deliveredSamples();
      report.put("mock_delivered_samples",delivered).put("capture_samples",capture.capturedSamples.get());
      runOnMainSync(()->{try{call("useRealMicrophone",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      require(!capture.mockInput.enabled(),"real microphone not restored");
      // Pending synthesis must not survive leaving/ending the session.
      send("이 문장은 취소되어야 해요.");
      runOnMainSync(()->{try{call("stopCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      long stopped=epoch();Thread.sleep(1500);
      require(epoch()==stopped && !(boolean)get("listening") && !synth.busy(),"late mock survived stop");
      require(audio.getMode()==modeBefore,"stop changed system audio mode");
      report.put("result","PASS");result.putString("result","PASS");
    } catch(Throwable e) {
      try{report.put("result","FAIL").put("error",e.toString()).put("status",get("lastStatus")).put("caption",get("microphoneTranscript"));}catch(Exception ignored){}
    } finally {
      try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"mock-voice.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
