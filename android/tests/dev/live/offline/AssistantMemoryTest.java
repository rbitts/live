package dev.live.offline;

public class AssistantMemoryTest {
  public static void main(String[] args) {
    AssistantMemory m = new AssistantMemory();
    m.watch("컵이 보이면 알려줘", 1000);
    assert m.due(1000);
    m.checked(1000);
    assert !m.due(15999) && m.due(16000);
    assert m.observed("컵이 있다", true, false, "컵이 보여요", 1100, 2000).isEmpty();
    assert m.observed("컵이 있다", true, false, "컵이 보여요", 1100, 2200).isEmpty() : "same frame is not independent confirmation";
    assert m.observed("컵이 있다", true, false, "컵이 보여요", 16100, 17000).equals("컵이 보여요");
    assert m.fulfilled();
    assert m.observed("컵이 있다", true, false, "다시 보여요", 70000, 71000).isEmpty();
    m.turn("내 컵은 파란색이야", "기억할게요", 72000);
    assert m.context(74000).contains("파란색") && m.context(74000).contains("과거 관측");
    m.clear();
    m.watch("", 30000);
    assert m.observed("빈 책상", false, true, "책상이 보여요", 31000, 32000).isEmpty() : "silent baseline";
    assert m.observed("책상 위 컵", false, true, "컵이 놓였어요", 47000, 48000).equals("컵이 놓였어요");
    assert m.observed("책상 위 사람", false, true, "사람이 왔어요", 63000, 64000).isEmpty() : "cooldown";
    assert m.observed("빈 책상", false, true, "컵이 없어졌어요", 100000, 113000).isEmpty() : "stale result";
    m.stopWatching();
    assert !m.due(200000);
    assert m.observed("빈 책상", false, true, "알림", 200000, 201000).isEmpty();
    m.configure(false, 60000);
    assert m.context(201000).isEmpty();
    m.turn("비밀", "답변", 201000);
    assert m.context(201001).isEmpty() : "opt out includes conversation";
    m.configure(true, 60000);
    m.watch("컵", 202000);
    assert !m.watching(262001) && m.task(262001).isEmpty() : "task expiry";
    for (int i=0; i<100; i++) m.turn("질문", "답변", 300000+i);
    assert m.size(300100)==4 : "bounded conversation";
    assert m.context(400000).isEmpty();
    m.watch("컵", 500000);
    assert !m.watching(499999) : "clock rollback";
    m.clear();
    assert m.size(500001)==0;
    System.out.println("Assistant memory: confirmation, duplicate suppression, baseline, cooldown, freshness, expiry, opt-out, bounds PASS");
  }
}
