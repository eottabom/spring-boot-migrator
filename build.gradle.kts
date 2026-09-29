// 루트에는 소스가 없다. 마이그레이션 태스크만 붙인다.
//   recipes/    OpenRewrite 레시피 jar (대상 프로젝트의 rewrite classpath)
//   runner/     러너 Gradle 플러그인 (stage 결정, 실행, 게이트, 결과)
//   guides/     버전별 가이드 (지원 범위, 체크리스트, 실패 힌트, deprecated API 대체)
//   schema/     가이드, 설정 파일, 결과, 재개 기록의 JSON Schema
//   init/       대상 프로젝트에 붙이는 Gradle init script (대상 프로젝트의 Gradle 안에서 돌아서 Groovy 로 둔다)
plugins {
    id("com.eottabom.migration")
}

// ./gradlew coverage: 두 모듈의 테스트와 JaCoCo 리포트를 만들고 README 의 커버리지 표와 뱃지를 갱신한다
val coverageModules = mapOf(
    "runner" to file("runner/build/reports/jacoco/test/jacocoTestReport.xml"),
    "recipes" to file("recipes/build/reports/jacoco/test/jacocoTestReport.xml"),
)

tasks.register("coverage") {
    group = "verification"
    description = "테스트 커버리지를 측정하고 README 의 커버리지 표와 뱃지를 갱신한다"
    dependsOn(gradle.includedBuild("runner").task(":jacocoTestReport"), ":recipes:jacocoTestReport")
    val readme = file("README.md")
    doLast {
        val counter = Regex("""<counter type="(\w+)" missed="(\d+)" covered="(\d+)"/>""")
        val totals = coverageModules.mapValues { (_, report) ->
            // 리포트 끝(</package> 뒤)의 counter 가 모듈 전체 합계다
            counter.findAll(report.readText().substringAfterLast("</package>"))
                .associate { match ->
                    val (type, missed, covered) = match.destructured
                    type to covered.toInt() * 100.0 / (missed.toInt() + covered.toInt())
                }
        }
        fun percent(value: Double) = "%.1f%%".format(value)
        fun color(value: Double) = when {
            value >= 90 -> "brightgreen"
            value >= 80 -> "green"
            value >= 70 -> "yellow"
            else -> "orange"
        }
        val table = (listOf("| Module | Line | Branch | Method |", "|---|---|---|---|") + totals.map { (module, total) ->
            "| $module | ${percent(total.getValue("LINE"))} | ${percent(total.getValue("BRANCH"))} | ${percent(total.getValue("METHOD"))} |"
        }).joinToString("\n")
        val badges = totals.map { (module, total) ->
            val line = total.getValue("LINE")
            "![$module coverage](https://img.shields.io/badge/${module}%20coverage-${percent(line).replace("%", "%25")}-${color(line)})"
        }.joinToString(" ")
        var text = readme.readText()
        for ((marker, content) in listOf("coverage" to table, "badges" to badges)) {
            val section = Regex("(<!-- $marker:start -->\n)[\\s\\S]*?(<!-- $marker:end -->)")
            check(section.containsMatchIn(text)) { "README.md 에 $marker:start / $marker:end 표시가 없다" }
            text = text.replace(section) { it.groupValues[1] + content + "\n" + it.groupValues[2] }
        }
        readme.writeText(text)
        println(table)
        println(badges)
    }
}
