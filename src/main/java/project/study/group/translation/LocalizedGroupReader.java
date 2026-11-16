package project.study.group.translation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import project.study.common.category.StudyCategory;
import project.study.group.dto.*;
import project.study.group.service.GroupApplicationService;
import project.study.group.service.GroupService;
import project.study.group.translation.TranslationCacheRepository.CacheKey;

/** 기존 읽기 서비스의 권한 검사와 트랜잭션이 끝난 뒤 번역을 붙인다. */
public class LocalizedGroupReader {
    private final GroupService groups;
    private final GroupApplicationService applications;
    private final GroupTranslationService translations;

    public LocalizedGroupReader(
            GroupService groups, GroupApplicationService applications, GroupTranslationService translations) {
        this.groups = groups;
        this.applications = applications;
        this.translations = translations;
    }

    /** 한 번에 옮기는 신청 수. 잠금·예약을 한 트랜잭션에서 하므로 신청이 쌓인 그룹이 수락·취소를 오래 막지 않게 앞쪽만 옮긴다. */
    static final int MAX_TRANSLATED_APPLICATIONS = 20;

    /** 그룹장용 신청 목록 — 그룹장 확인이 끝난 응답의 신청 소개 글만 옮긴다. 오래된 신청부터 일정 수까지다 (BY-1301). */
    public GroupApplicationsResponse applications(Long userId, long groupId, String acceptLanguage) {
        var response = applications.applications(userId, groupId);
        var language = TranslationLanguage.resolve(acceptLanguage);
        if (language.isEmpty()) return response;
        var texts = response.applications().stream()
                .limit(MAX_TRANSLATED_APPLICATIONS)
                .map(a -> new TranslationText(a.applicationId(), TranslationField.INTRODUCTION, a.introduction()))
                .toList();
        var results = translations.translate(texts, language.get());
        var items = response.applications().stream()
                .map(a -> {
                    String text =
                            results.get(new CacheKey(a.applicationId(), TranslationField.INTRODUCTION, language.get()));
                    return text == null
                            ? a
                            : a.withTranslation(new GroupApplicationTranslation(
                                    language.get().locale(), text));
                })
                .toList();
        return new GroupApplicationsResponse(response.remainingSeats(), items);
    }

    public MyGroupsResponse myGroups(Long userId, String acceptLanguage) {
        var response = groups.myGroups(userId);
        var language = TranslationLanguage.resolve(acceptLanguage);
        if (language.isEmpty()) return response;
        var texts = response.groups().stream()
                .map(g -> new TranslationText(g.groupId(), TranslationField.NAME, g.name()))
                .toList();
        var results = translations.translate(texts, language.get());
        var cards = response.groups().stream()
                .map(g -> new MyGroupsResponse.MyGroupCard(
                        g.groupId(),
                        g.name(),
                        g.category(),
                        g.visibility(),
                        g.joinPolicy(),
                        g.memberCount(),
                        g.studyingCount(),
                        g.owner(),
                        g.newApplicationCount(),
                        g.members(),
                        value(g.groupId(), language.get(), results)))
                .toList();
        return new MyGroupsResponse(
                cards, response.pendingApplicationCount(), response.ownedGroupCount(), response.limits());
    }

    public GroupHomeResponse home(Long userId, long groupId, String acceptLanguage) {
        var response = groups.home(userId, groupId);
        var language = TranslationLanguage.resolve(acceptLanguage);
        if (language.isEmpty()) return response;
        var results = translations.translate(texts(groupId, response.name(), response.description()), language.get());
        return new GroupHomeResponse(
                response.groupId(),
                response.name(),
                response.visibility(),
                response.joinPolicy(),
                response.category(),
                response.description(),
                response.memberCount(),
                response.myRole(),
                response.notifyEnabled(),
                response.newApplicationCount(),
                response.studyingCount(),
                response.members(),
                value(groupId, language.get(), results));
    }

    public PublicGroupPageResponse publicGroups(
            Long userId, String query, StudyCategory category, int page, int size, String acceptLanguage) {
        var response = groups.publicGroups(userId, query, category, page, size);
        var language = TranslationLanguage.resolve(acceptLanguage);
        if (language.isEmpty()) return response;
        var texts = response.groups().stream()
                .map(g -> new TranslationText(g.groupId(), TranslationField.NAME, g.name()))
                .toList();
        var results = translations.translate(texts, language.get());
        var cards = response.groups().stream()
                .map(g -> new PublicGroupPageResponse.PublicGroupCard(
                        g.groupId(),
                        g.name(),
                        g.category(),
                        g.joinPolicy(),
                        g.memberCount(),
                        g.weeklyAverageFocusSec(),
                        g.weeklyTotalFocusSec(),
                        g.studyingCount(),
                        g.member(),
                        value(g.groupId(), language.get(), results)))
                .toList();
        return new PublicGroupPageResponse(cards, response.hasNext());
    }

    public PublicGroupDetailResponse publicGroup(Long userId, long groupId, String acceptLanguage) {
        var response = groups.publicGroup(userId, groupId);
        var language = TranslationLanguage.resolve(acceptLanguage);
        if (language.isEmpty()) return response;
        var results = translations.translate(texts(groupId, response.name(), response.description()), language.get());
        return new PublicGroupDetailResponse(
                response.groupId(),
                response.name(),
                response.category(),
                response.joinPolicy(),
                response.description(),
                response.memberCount(),
                response.owner(),
                response.weeklyAverageFocusSec(),
                response.weeklyTotalFocusSec(),
                response.members(),
                response.applyState(),
                response.unavailableReason(),
                response.application(),
                value(groupId, language.get(), results));
    }

    private static List<TranslationText> texts(long groupId, String name, String description) {
        List<TranslationText> texts = new ArrayList<>();
        texts.add(new TranslationText(groupId, TranslationField.NAME, name));
        if (description != null && !description.isBlank())
            texts.add(new TranslationText(groupId, TranslationField.DESCRIPTION, description));
        return texts;
    }

    private static GroupTranslation value(long groupId, TranslationLanguage language, Map<CacheKey, String> results) {
        String name = results.get(new CacheKey(groupId, TranslationField.NAME, language));
        String description = results.get(new CacheKey(groupId, TranslationField.DESCRIPTION, language));
        return name == null && description == null ? null : new GroupTranslation(language.locale(), name, description);
    }
}
