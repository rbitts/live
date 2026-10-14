package dev.live.offline;
import android.app.*;import android.os.*;import java.io.*;import org.json.*;
/** Explicit offline provisioning through the same verified importer as the app UI. */
public final class ModelInstallInstrumentation extends Instrumentation {
  private Bundle args;
  public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
  public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();long start=SystemClock.elapsedRealtime();
    try{String source=args.getString("bundle");if(source==null)throw new IllegalArgumentException("bundle required");
      File root=ModelBundle.install(new FileInputStream(source),getTargetContext().getFilesDir());
      report.put("root",root.getPath()).put("status","installed").put("elapsed_ms",SystemClock.elapsedRealtime()-start);
      result.putString("result","INSTALLED");
    }catch(Throwable error){try{report.put("status","failed").put("error",error.toString());}catch(Exception ignored){}result.putString("error",error.toString());}
    finally{try(FileWriter out=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"model-install.json"))){out.write(report.toString(2));}catch(Exception ignored){}
      finish("INSTALLED".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}
  }
}
