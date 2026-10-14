package dev.live.offline;
import android.app.*;import android.content.*;import android.os.*;import java.io.*;
/** No speakers or sensors. Explicit pinned candidates and frozen inputs only. */
public final class CookingProfileInstrumentation extends Instrumentation {
  static{System.loadLibrary("live_jni");}
  private Bundle args;
  private native String profile(String config,String cases,String output,int pixels,boolean compact,int repeats);
  public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
  public void onStart(){Bundle result=new Bundle();Activity activity=null;
    try{
      activity=startActivitySync(new Intent(getTargetContext(),ProfileActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      File dir=getTargetContext().getExternalFilesDir(null);
      String model=args.getString("model","installed");
      if(!model.matches("[a-zA-Z0-9_.-]+"))throw new IllegalArgumentException("model name");
      File config=model.equals("installed")?new File(getTargetContext().getFilesDir(),"models/vlm/config.json"):new File(getTargetContext().getFilesDir(),"candidates/"+model+"/config.json");
      int pixels=Integer.parseInt(args.getString("pixels","0"));boolean compact=Boolean.parseBoolean(args.getString("compact","false"));
      File output=new File(dir,"cooking-profile-"+model+"-"+pixels+"-"+compact+".jsonl");
      result.putString("result",profile(config.getPath(),new File(dir,"cooking-cases.json").getPath(),output.getPath(),pixels,compact,Integer.parseInt(args.getString("repeats","1"))));
    }catch(Throwable e){result.putString("error",e.toString());}
    finally{if(activity!=null){Activity end=activity;runOnMainSync(end::finish);}finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}
  }
}
