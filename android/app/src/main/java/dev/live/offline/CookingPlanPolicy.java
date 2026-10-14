package dev.live.offline;

import java.util.List;

/** Narrow regression guard, not a general recipe correctness checker. */
final class CookingPlanPolicy {
  static String issue(String request,List<String> steps){
    String text=String.join(" ",steps);
    if(request.matches("(?s).*(달걀|계란)\\s*(프라이|후라이)(?!팬).*")){
      if(text.matches("(?s).*(저어|젓|섞|볶).*"))
        return "달걀프라이는 노른자를 유지합니다. 팬에 기름을 먼저 두르고 달걀을 넣으며, 달걀을 젓거나 섞거나 볶는 안내를 제거하세요.";
    }
    return "";
  }
}
