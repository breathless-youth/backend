package project.study;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "project.study", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // allowEmptyShould: 해당 접미사의 클래스가 아직 없는 시점에도 규칙이 실패하지 않게 한다
    @ArchTest
    static final ArchRule controllerShouldNotAccessRepository = noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .because("컨트롤러는 서비스를 거쳐야 한다")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noFieldInjection = noClasses()
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Autowired")
            .because("생성자 주입만 사용한다");

    @ArchTest
    static final ArchRule servicesShouldBeInDomainPackages = classes()
            .that()
            .haveSimpleNameEndingWith("Service")
            .should()
            .resideInAPackage("project.study..")
            .because("서비스는 도메인 패키지 안에 있어야 한다")
            .allowEmptyShould(true);
}
