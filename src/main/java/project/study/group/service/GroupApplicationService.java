package project.study.group.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ForbiddenException;
import project.study.common.exception.NotFoundException;
import project.study.common.text.Graphemes;
import project.study.group.ContactDetector;
import project.study.group.GroupVisibility;
import project.study.group.dto.GroupApplicationCreateRequest;
import project.study.group.dto.GroupApplicationCreateResponse;
import project.study.group.dto.GroupApplicationsResponse;
import project.study.group.dto.GroupApplicationsResponse.GroupApplicationItem;
import project.study.group.dto.MyApplicationsResponse;
import project.study.group.dto.MyApplicationsResponse.MyApplicationItem;
import project.study.group.dto.ReportRequest;
import project.study.group.push.GroupNotifications;
import project.study.group.report.GroupReports;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupApplicationRepository.ApplicationRow;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.group.repository.ReportRepository;
import project.study.user.image.ProfileImageUrls;

/**
 * 공개 그룹 신청 보내기·취소·목록·수락·거절·신고 (BY-1124).
 *
 * <p>락 순서는 그룹 행 → 사용자 락 → 신청 행이다({@link GroupLocks}). 신청 보내기는 그룹 행(신청 대상)과 신청자의 사용자 락을 잡고 센 뒤
 * 넣고, 수락은 신청받은 그룹 행과 <b>신청자</b>의 사용자 락을 잡는다 — 신청이 멤버 자리로 바뀌는 사이에 신청자의 "그룹+신청 5개" 계산이 두 쪽
 * 합을 어긋나게 읽지 못하게 한다. 거절·취소는 개수를 줄이기만 하므로 사용자 락이 필요 없다.
 *
 * <p>"답을 기다리는 신청"은 기한 안의 {@code PENDING}뿐이다. 만료 작업이 아직 안 돌았어도 기한이 지난 신청은 개수·목록에서 빠지고,
 * 수락·거절·취소는 {@code APPLICATION_NOT_PENDING}이다.
 */
@Service
@RequiredArgsConstructor
public class GroupApplicationService {

    /** 신청이 답을 기다리는 기간 */
    static final Duration APPLICATION_TERM = Duration.ofDays(3);

    private static final String UNKNOWN_NICKNAME = "새 사용자";

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupApplicationRepository applications;
    private final GroupLocks locks;
    private final GroupAccess access;
    private final GroupNotifications notifications;
    private final GroupReports reports;
    private final ProfileImageUrls profileImageUrls;
    private final Clock clock;

    /**
     * 신청한다. 검사 순서(Swagger 설명을 따르고, 설명이 정하지 않은 부분은 아래 순서다): 연락처 400 → 그룹 행 잠금(없음·삭제·비공개는 404) → 이미 멤버 → 기한 안 신청 있음 → 내보낸 지
     * 3일 → 멤버 6명 → 사용자 락 → "그룹+신청 5개" → "신청 3개". 같은 쌍에 기한만 지난 {@code PENDING}이 남아 있으면 넣기 전에
     * EXPIRED로 닫고 그 신청의 만료 푸시를 보낸다 — 조건부 UPDATE가 바꾼 경우에만이라 만료 작업과 겹쳐도 한 번만 나간다.
     */
    @Transactional
    public GroupApplicationCreateResponse apply(Long userId, long groupId, GroupApplicationCreateRequest request) {
        String introduction = Graphemes.normalize(request.introduction());
        if (ContactDetector.containsContact(introduction)) {
            throw new BadRequestException(ErrorCode.CONTACT_NOT_ALLOWED, "신청 소개 글에 연락처나 링크를 넣을 수 없어요");
        }

        Instant now = now();
        // 비공개 그룹은 있는지도 알려 주지 않는다 — 없는 그룹과 같은 404다
        GroupRow group = groups.lockLive(groupId)
                .filter(found -> found.visibility() == GroupVisibility.PUBLIC)
                .orElseThrow(() -> new NotFoundException(ErrorCode.GROUP_NOT_FOUND, "없거나 사라진 그룹이에요"));

        if (members.findLive(groupId, userId).isPresent()) {
            throw new ConflictException(ErrorCode.ALREADY_MEMBER, "이미 이 그룹의 멤버예요");
        }
        if (applications.findPending(groupId, userId, now).isPresent()) {
            throw new ConflictException(ErrorCode.ALREADY_APPLIED, "이 그룹에 답을 기다리는 신청이 이미 있어요");
        }
        if (members.recentlyKicked(groupId, userId, now.minus(GroupService.KICK_COOLDOWN))) {
            throw new ConflictException(ErrorCode.RECENTLY_KICKED, "이 그룹에서 내보내진 지 3일이 지나야 다시 신청할 수 있어요");
        }
        if (members.countLive(groupId) >= GroupService.MAX_MEMBERS) {
            throw new ConflictException(ErrorCode.GROUP_FULL, "그룹 멤버가 이미 6명이에요");
        }

        locks.lockUser(userId);
        int pending = applications.countPendingOfUser(userId, now);
        if (members.countLiveGroupsOfUser(userId) + pending >= GroupService.MAX_GROUP_SLOTS) {
            throw new ConflictException(ErrorCode.GROUP_SLOT_LIMIT, "들어간 그룹과 신청 중인 그룹이 이미 5개예요");
        }
        if (pending >= GroupService.MAX_PENDING_APPLICATIONS) {
            throw new ConflictException(ErrorCode.APPLICATION_LIMIT, "답을 기다리는 신청이 이미 3곳이에요");
        }

        // 기한만 지나고 만료 작업이 아직 안 닫은 옛 신청은 부분 unique에 걸리므로 먼저 닫는다
        if (applications.expireStale(groupId, userId, now) > 0) {
            notifications.applicationExpired(userId, group.name());
        }
        Instant expiresAt = now.plus(APPLICATION_TERM);
        long applicationId = applications.insert(groupId, userId, introduction, now, expiresAt);
        notifications.applicationReceived(
                group.ownerId(),
                groupId,
                group.name(),
                applications.findNickname(userId).orElse(UNKNOWN_NICKNAME));
        return new GroupApplicationCreateResponse(applicationId, expiresAt);
    }

    /** 그룹장용 목록 — 그룹장만, 기한 안에서 답을 기다리는 신청을 신청한 순서로. 남은 자리는 6명에서 지금 멤버 수를 뺀 값이다. */
    @Transactional(readOnly = true)
    public GroupApplicationsResponse applications(Long userId, long groupId) {
        access.requireOwner(groupId, userId, false);
        Instant now = now();
        int remainingSeats = Math.max(0, GroupService.MAX_MEMBERS - members.countLive(groupId));
        List<GroupApplicationItem> items = applications.findPendingOfGroup(groupId, now).stream()
                .map(row -> new GroupApplicationItem(
                        row.applicationId(),
                        row.userId(),
                        row.nickname(),
                        profileImageUrls.of(row.profileImage()),
                        row.introduction(),
                        row.createdAt(),
                        row.expiresAt()))
                .toList();
        return new GroupApplicationsResponse(remainingSeats, items);
    }

    /** 내 신청 목록 — 기한 안에서 답을 기다리는 것만 신청한 순서(오래된 것부터). */
    @Transactional(readOnly = true)
    public MyApplicationsResponse myApplications(Long userId) {
        List<MyApplicationItem> items = applications.findPendingOfUser(userId, now()).stream()
                .map(row -> new MyApplicationItem(
                        row.applicationId(),
                        row.groupId(),
                        row.groupName(),
                        row.category(),
                        row.createdAt(),
                        row.expiresAt()))
                .toList();
        return new MyApplicationsResponse(items);
    }

    /**
     * 신청한 사람이 취소한다. 남의 신청이거나 없는 신청이면 똑같이 404 {@code NOT_FOUND}다. 취소는 한도에서 빠지기만 하므로 푸시도 사용자 락도
     * 없다. 신청 행을 잠가 같은 신청의 동시 수락·거절과 한쪽만 이기게 한다.
     */
    @Transactional
    public void cancel(Long userId, long applicationId) {
        Instant now = now();
        ApplicationRow application = applications
                .lock(applicationId)
                .filter(found -> found.userId() == userId)
                .orElseThrow(GroupApplicationService::applicationNotFound);
        requireWaiting(application, now);
        markAnswered(application, GroupApplicationRepository.CANCELED, now);
    }

    /**
     * 수락한다 — 신청받은 그룹의 그룹장만. 순서: 그룹 행 잠금(그룹장 아님 403) → 신청자 사용자 락 → 신청 행 잠금(기한 안 {@code PENDING} 아님 409)
     * → 멤버 6명이면 409 {@code GROUP_FULL}(신청은 그대로 남는다) → 멤버 만들기 + ACCEPTED → 푸시. 신청한 사람의 5개 한도는 다시 보지 않는다 —
     * 신청이 이미 그 5개 안에 들어 있다.
     */
    @Transactional
    public void accept(Long userId, long applicationId) {
        Instant now = now();
        ApplicationRow found =
                applications.find(applicationId).orElseThrow(GroupApplicationService::applicationNotFound);
        GroupRow group = lockOwnedGroup(userId, found);
        // 신청자의 사용자 락은 신청 행 락보다 먼저 잡는다 — 신청 보내기·만들기와 같은 순서라 서로 기다리다 막히지 않는다
        locks.lockUser(found.userId());
        ApplicationRow application = requireWaiting(
                applications.lock(applicationId).orElseThrow(GroupApplicationService::applicationNotFound), now);

        if (members.countLive(group.id()) >= GroupService.MAX_MEMBERS) {
            throw new ConflictException(ErrorCode.GROUP_FULL, "그룹 멤버가 이미 6명이에요");
        }
        markAnswered(application, GroupApplicationRepository.ACCEPTED, now);
        members.insert(group.id(), application.userId(), GroupMemberRepository.VIA_APPLICATION, now);
        notifications.applicationAccepted(application.userId(), group.id(), group.name());
    }

    /** 거절한다 — 수락과 같은 권한·상태 검사이고 자리는 보지 않는다. 신청한 사람에게 그 그룹 시험·목표로 거른 거절 푸시가 간다. */
    @Transactional
    public void decline(Long userId, long applicationId) {
        declineWaiting(userId, applicationId, now());
    }

    /**
     * 신청을 신고한다 — 거절과 같은 권한·상태 검사로 거절한 뒤 신고를 남기고 운영 채널에 알린다. 신청한 사람에게는 거절 푸시만 가서 신고됐다는 것이
     * 드러나지 않는다. 신청한 사람을 차단하지 않는다(설계 — 위키 8절에 차단이 없다).
     */
    @Transactional
    public void report(Long userId, long applicationId, ReportRequest request) {
        Instant now = now();
        Declined declined = declineWaiting(userId, applicationId, now);
        reports.record(
                ReportRepository.APPLICATION,
                userId,
                declined.application().userId(),
                declined.group().id(),
                declined.application().id(),
                request.reason(),
                now);
    }

    private record Declined(GroupRow group, ApplicationRow application) {}

    /** 거절과 신청 신고가 함께 쓰는 거절: 그룹 행 잠금(그룹장 아님 403) → 신청 행 잠금(기한 안 {@code PENDING} 아님 409) → DECLINED → 거절 푸시. */
    private Declined declineWaiting(long userId, long applicationId, Instant now) {
        ApplicationRow found =
                applications.find(applicationId).orElseThrow(GroupApplicationService::applicationNotFound);
        GroupRow group = lockOwnedGroup(userId, found);
        ApplicationRow application = requireWaiting(
                applications.lock(applicationId).orElseThrow(GroupApplicationService::applicationNotFound), now);

        markAnswered(application, GroupApplicationRepository.DECLINED, now);
        notifications.applicationDeclined(application.userId(), group.name(), group.category());
        return new Declined(group, application);
    }

    /**
     * 신청받은 그룹을 잠그고 요청자가 그룹장인지 본다(락 순서 ①). 신청의 그룹이 사라졌으면 그 신청은 누구에게나 없는 신청이라 404
     * {@code NOT_FOUND}이고, 그룹장이 아니면 403 {@code NOT_GROUP_OWNER}다.
     */
    private GroupRow lockOwnedGroup(long userId, ApplicationRow application) {
        GroupRow group =
                groups.lockLive(application.groupId()).orElseThrow(GroupApplicationService::applicationNotFound);
        if (group.ownerId() != userId) {
            throw new ForbiddenException(ErrorCode.NOT_GROUP_OWNER, "그룹장만 할 수 있어요");
        }
        return group;
    }

    /** 기한 안의 {@code PENDING}이어야 답할 수 있다. 기한이 지난 신청은 만료 작업 전이라도 끝난 신청이다. */
    private static ApplicationRow requireWaiting(ApplicationRow application, Instant now) {
        if (!GroupApplicationRepository.PENDING.equals(application.status())
                || !application.expiresAt().isAfter(now)) {
            throw notPending();
        }
        return application;
    }

    /** 잠근 신청을 {@code PENDING}에서 바꾼다. 잠갔으므로 0건은 일어나지 않지만, 일어나면 이미 답한 신청으로 본다. */
    private void markAnswered(ApplicationRow application, String toStatus, Instant now) {
        if (applications.answer(application.id(), GroupApplicationRepository.PENDING, toStatus, now) == 0) {
            throw notPending();
        }
    }

    /** DB는 마이크로초까지만 담으므로 응답으로 돌려주는 시각과 저장된 시각이 같도록 맞춘다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static NotFoundException applicationNotFound() {
        return new NotFoundException("없는 신청이에요");
    }

    private static ConflictException notPending() {
        return new ConflictException(ErrorCode.APPLICATION_NOT_PENDING, "이미 답했거나 취소·만료된 신청이에요");
    }
}
