package project.study.room.dto;

// focusSec: 클라이언트가 주기 보고하는 순공 타이머(초). 옛 이름 studySeconds는 받지 않는다 (룸 출시와 함께 계약 변경)
// micOn·onBreak: 그룹 공부방에서만 저장·방송한다(BY-1124, websocket.html §11.3). 초대코드 방에서 오면 저장도 방송도 하지 않는다
public record StateUpdatePayload(
        Boolean cameraOn, String focusState, Integer focusSec, Boolean micOn, Boolean onBreak) {

    /** 그룹 공부방 필드가 없는 보고 — 초대코드 방의 지금 계약 그대로다. */
    public StateUpdatePayload(Boolean cameraOn, String focusState, Integer focusSec) {
        this(cameraOn, focusState, focusSec, null, null);
    }
}
