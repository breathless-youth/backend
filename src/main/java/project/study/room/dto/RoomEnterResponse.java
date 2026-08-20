package project.study.room.dto;

import java.util.List;

public record RoomEnterResponse(boolean graceRejoin, Boolean cameraOn, List<IceServer> iceServers, int iceTtlSeconds) {

    public record IceServer(List<String> urls, String username, String credential) {}
}
