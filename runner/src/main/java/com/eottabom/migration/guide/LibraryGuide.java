package com.eottabom.migration.guide;

import java.util.List;

/**
 * guides/libraries/&lt;이름&gt;.yml. stage 와 상관없이 resolve 된 버전이 바뀔 때 거는 항목.
 *
 * @param library group:artifact
 */
public record LibraryGuide(String library, List<ChecklistItem> checklist) {
}
