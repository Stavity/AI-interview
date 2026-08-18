package interview.guide.modules.multiround.service;

import java.util.List;
import java.util.Set;

public final class MultiRoundStateMachine {
  private static final List<String> DEFAULT_ROUNDS = List.of("ALGORITHM", "TECHNICAL_1", "TECHNICAL_2", "HR");
  private static final Set<String> TYPES = Set.copyOf(DEFAULT_ROUNDS);

  private MultiRoundStateMachine() {}

  public static List<String> defaultRounds() { return DEFAULT_ROUNDS; }

  public static void validatePlan(List<String> types) {
    if (types.size() < 2 || types.size() > 4 || !types.getFirst().equals("ALGORITHM") || !TYPES.containsAll(types)) {
      throw new IllegalArgumentException("多轮面试必须以算法轮开始，且总轮数为 2 到 4");
    }
    if (types.stream().filter("ALGORITHM"::equals).count() != 1 || types.stream().filter("HR"::equals).count() > 1) {
      throw new IllegalArgumentException("算法轮必须且只能出现一次，HR 最多一次");
    }
    int hr = types.indexOf("HR");
    if (hr >= 0 && hr != types.size() - 1) {
      throw new IllegalArgumentException("HR 轮只能位于最后");
    }
  }
}
