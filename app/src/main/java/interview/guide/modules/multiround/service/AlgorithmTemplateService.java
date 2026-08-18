package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AlgorithmTemplateService {
  private static final Map<String, String> TEMPLATES = Map.of(
      "JAVA", "public class Solution {\n  public int solve(int[] nums) {\n    // 在此实现思路；本期不会执行代码\n    return 0;\n  }\n}\n",
      "PYTHON", "class Solution:\n    def solve(self, nums):\n        # 在此实现思路；本期不会执行代码\n        return 0\n",
      "CPP", "#include <vector>\nusing namespace std;\n\nclass Solution {\npublic:\n  int solve(vector<int>& nums) {\n    // 在此实现思路；本期不会执行代码\n    return 0;\n  }\n};\n");

  public String templateFor(String language) {
    String template = TEMPLATES.get(language);
    if (template == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的算法语言: " + language);
    }
    return template;
  }
}
