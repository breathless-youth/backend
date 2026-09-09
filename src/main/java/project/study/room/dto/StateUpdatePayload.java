package project.study.room.dto;

// focusSec: 클라이언트가 주기 보고하는 순공 타이머(초). 옛 이름 studySeconds는 받지 않는다 (룸 출시와 함께 계약 변경)
public record StateUpdatePayload(Boolean cameraOn, String focusState, Integer focusSec) {}
