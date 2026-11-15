package project.study.room.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.room.dto.RoomMember;
import project.study.shop.dto.MascotAppearance;
import project.study.shop.service.DecorationService;

/** 멤버 객체에 고른 마스코트와 낀 소품을 붙인다 (BY-1288). 방 인원만큼을 한 번에 읽는다. */
@Component
@RequiredArgsConstructor
public class RoomMemberMascots {

    private final DecorationService decorations;

    public List<RoomMember> attach(List<RoomMember> members) {
        if (members.isEmpty()) {
            return members;
        }
        Map<Long, MascotAppearance> mascots = decorations.appearancesOf(
                members.stream().map(RoomMember::userId).toList());
        return members.stream()
                .map(member -> member.withMascot(mascots.get(member.userId())))
                .toList();
    }
}
