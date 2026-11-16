package project.study.group.translation;

/**
 * 선언 순서가 곧 시도 순서다. Azure F0는 결제 수단이 없어 과금 경로가 없으므로 먼저 쓰고, 초과 과금 구조인 Google은 그다음, 평생 총량인 DeepL은
 * 마지막 예비분이다 (ADR-0046 결정 4, BY-1294). DB에는 이름으로 저장하므로 순서를 바꿔도 장부·캐시에 영향이 없다.
 */
public enum TranslationProvider {
    AZURE,
    GOOGLE,
    DEEPL
}
