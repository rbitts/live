package dev.live.offline;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;

/** Explicit device benchmark. No sensor input or speaker output; fixed fixtures only. */
public final class ModelProfileInstrumentation extends Instrumentation {
  static { System.loadLibrary("live_jni"); }
  private Bundle args;
  private native String profileNative(String root, String wav, String image,
      String variant, String role, int repeats, String result);
  @Override public void onCreate(Bundle args) { super.onCreate(args);this.args=args;start(); }
  @Override public void onStart() {
    Bundle result = new Bundle(); Activity activity = null;
    try {
      activity = startActivitySync(new Intent(getTargetContext(), ProfileActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      File dir = getTargetContext().getExternalFilesDir(null);
      String variant=args.getString("variant","baseline"),role=args.getString("role","all");
      File output = new File(dir,"profile-"+variant+"-"+role+".jsonl");
      result.putString("result",profileNative(new File(getTargetContext().getFilesDir(),"models").getPath(),
          new File(dir,"question.wav").getPath(),new File(dir,"scene.jpg").getPath(),
          variant,role,Integer.parseInt(args.getString("repeats","3")),output.getPath()));
      result.putString("output",output.getPath());
    } catch(Throwable e) {result.putString("error",e.toString());}
    finally {
      if(activity!=null){Activity end=activity;runOnMainSync(end::finish);}
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
