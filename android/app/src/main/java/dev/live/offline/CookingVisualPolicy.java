package dev.live.offline;

import java.util.Locale;

/** Translate only named visible objects. Never translate a step into a claim that it happened. */
final class CookingVisualPolicy {
  private static final String[][] OBJECTS = {
    {"프라이팬","frying pan"},{"후라이팬","frying pan"},{"머그컵","cup or drinking glass"},
    {"달걀","egg"},{"계란","egg"},{"토마토","tomato"},{"양파","onion"},{"오이","cucumber"},
    {"감자","potato"},{"당근","carrot"},{"버섯","mushroom"},{"양배추","cabbage"},
    {"상추","lettuce"},{"브로콜리","broccoli"},{"파프리카","bell pepper"},{"마늘","garlic"},
    {"사과","apple"},{"바나나","banana"},{"레몬","lemon"},{"두부","tofu"},
    {"식빵","bread"},{"빵","bread"},{"피자","pizza"},{"치즈","cheese"},
    {"컵","cup or drinking glass"},{"잔","cup or drinking glass"},{"팬","frying pan"},{"냄비","cooking pot"},
    {"도마","cutting board"},{"접시","plate"},{"그릇","bowl"},{"칼","knife"}
  };
  static String question(String step) {
    // The first mentioned supported object is the target, not every object in the recipe.
    // Longest match resolves 컵/머그컵, 팬/프라이팬. Unknown nouns abstain.
    int first=Integer.MAX_VALUE,length=0;String target="";
    for(String[] entry:OBJECTS){int at=step.indexOf(entry[0]);
      if(at>=0 && !wordEnd(step,at+entry[0].length()))continue;
      if(at>=0 && (at<first || (at==first && entry[0].length()>length))){first=at;length=entry[0].length();target=entry[1];}}
    return target.isEmpty()?"":"Is a "+target+" clearly visible?";
  }
  private static boolean wordEnd(String text,int at){
    if(at==text.length())return true;
    char next=text.charAt(at);
    return Character.isWhitespace(next) || ",.!?·()을를에의이가은는과와로으도만".indexOf(next)>=0;
  }
  static boolean positive(String response) {
    String value=response.trim().toUpperCase(Locale.ROOT);
    return value.equals("YES") || value.equals("YES.");
  }
  static String reminder(String step,String response){
    // Conditional guidance, not an assertion that all ingredients are ready or a step is done.
    if(!positive(response) || question(step).isEmpty() || step.length()>90)return "";
    return "준비되면 "+step;
  }
}
