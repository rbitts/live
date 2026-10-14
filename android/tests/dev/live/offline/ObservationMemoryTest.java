package dev.live.offline;

public class ObservationMemoryTest {
  public static void main(String[] args) {
    ObservationMemory memory = new ObservationMemory();
    byte[] bytes = {1, 2, 3};
    memory.observe(bytes, 1000);
    bytes[0] = 9;
    var first = memory.question(2000);
    assert first.size() == 1 && first.get(0).current;
    assert first.get(0).frame.jpeg[0] == 1;
    memory.observe(new byte[] {4}, 4000);
    var next = memory.question(4001);
    assert next.size() == 2;
    assert !next.get(0).current && next.get(1).current;
    memory.observe(new byte[] {5}, 8000);
    assert memory.question(7999).stream().noneMatch(e -> e.current) : "future frame";
    assert memory.question(12000).stream().noneMatch(e -> e.current) : "stale frame";
    memory.cameraOff();
    assert memory.question(13000).size() == 2 : "history retained";
    memory.reset();
    assert memory.question(14000).isEmpty() : "session reset";
    for (int i = 0; i < 20; i++) {
      memory.observe(new byte[] {1}, 20000 + i * 1000);
      assert memory.question(20000 + i * 1000).size() <= 3;
    }
    memory.configure(0, 60000);
    assert memory.snapshot(39000).isEmpty() : "disable immediately erases history";
    memory.observe(new byte[] {2}, 40000);
    assert memory.question(40001).size() == 1 : "current view still works without history";
    memory.cameraOff();
    assert memory.question(40002).isEmpty() : "disabled memory cannot recall";
    memory.configure(1, 60000);
    memory.observe(new byte[] {3}, 50000);
    memory.question(50001);
    memory.observe(new byte[] {4}, 60000);
    memory.question(60001);
    assert memory.snapshot(60002).size() == 1 : "capacity applies immediately";
    memory.forget(60000);
    assert memory.question(60003).isEmpty() : "deleted frame is not reused as current";
    memory.observe(new byte[] {5}, 70000);
    memory.question(70001);
    memory.cameraOff();
    assert memory.question(130001).isEmpty() : "expired history is never inference evidence";
    memory.observe(new byte[] {6}, 140000);
    memory.question(140001);
    assert memory.snapshot(139000).isEmpty() : "clock regression fails closed";
    System.out.println(
        "Observation memory: freshness, future frame, immutable bytes, camera off, reset, bounds"
            + " PASS");
  }
}
