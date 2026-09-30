package com.eottabom.migration;

import java.util.Arrays;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 패키지 의존 방향. plugin 은 config 와 pipeline 을 부르고, pipeline 은 step 을 부르고, step 은 도메인 패키지를
 * 부른다. 도메인 패키지(plan, guide, result, project, workspace, config) 는 실행 흐름을 모른다.
 */
class ArchitectureTests {

	private static final String BASE = "com.eottabom.migration.";

	private static JavaClasses classes;

	@BeforeAll
	static void importClasses() {
		classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.eottabom.migration");
	}

	@ParameterizedTest(name = "[{index}] {0} 는 {1} 를 모른다")
	@CsvSource(delimiter = '|',
			value = {
					"io|config,console,gradle,guide,pipeline,plan,plugin,project,recipe,result,stage,version,workspace",
					"version|config,console,gradle,guide,io,pipeline,plan,plugin,project,recipe,result,stage,workspace",
					"stage|config,console,gradle,guide,io,pipeline,plan,plugin,project,recipe,result,version,workspace",
					"config|console,gradle,guide,pipeline,plan,plugin,project,recipe,result,workspace",
					"guide|config,console,gradle,pipeline,plan,plugin,project,recipe,result,workspace",
					"project|config,console,gradle,guide,pipeline,plan,plugin,recipe,result,workspace",
					"plan|console,gradle,pipeline,plugin,recipe,result,workspace",
					"result|config,console,gradle,pipeline,plan,plugin,project,recipe,workspace",
					"workspace|config,console,gradle,guide,pipeline,plan,plugin,project,recipe,result",
					"recipe|config,console,gradle,guide,pipeline,plugin,project,result,workspace",
					"gradle|config,console,guide,pipeline,plan,plugin,project,recipe,result,workspace",
					"console|config,gradle,pipeline,plugin,result,workspace", "pipeline.step|plugin" })
	void packageDoesNotDependOn(String pkg, String forbidden) {
		String[] packages = Arrays.stream(forbidden.split(","))
			.map((name) -> BASE + name + "..")
			.toArray(String[]::new);
		ArchRuleDefinition.noClasses()
			.that()
			.resideInAPackage(BASE + pkg + "..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage(packages)
			.check(classes);
	}

	@Test
	void stepsDoNotCallBackIntoPipeline() {
		ArchRuleDefinition.noClasses()
			.that()
			.resideInAPackage(BASE + "pipeline.step..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("com.eottabom.migration.pipeline")
			.check(classes);
	}

	/** step 이 같이 쓰는 동작은 step 이 아닌 도우미(RecipeRun, TestRun 등)로 뺀다 */
	@Test
	void stepsDoNotDependOnOtherSteps() {
		ArchRuleDefinition.noClasses()
			.that()
			.haveSimpleNameEndingWith("Step")
			.should()
			.dependOnClassesThat()
			.haveSimpleNameEndingWith("Step")
			.check(classes);
	}

}
