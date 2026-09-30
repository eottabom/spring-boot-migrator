package com.eottabom.migration.git;

/**
 * 대상 프로젝트의 작업 트리 상태.
 *
 * @param repository 대상 프로젝트가 git 저장소의 최상위 디렉토리다
 * @param dirty 커밋되지 않은 변경이 있다 (저장소가 아니면 false)
 */
public record WorkingTree(boolean repository, boolean dirty) {

	public String describe() {
		if (!this.repository) {
			return "아님";
		}
		return this.dirty ? "커밋되지 않은 변경 있음" : "깨끗함";
	}

}
