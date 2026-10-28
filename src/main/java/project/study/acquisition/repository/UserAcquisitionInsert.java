package project.study.acquisition.repository;

/** 사용자 행이 아직 없을 때만 넣는 쓰기. Spring Data 조각 인터페이스다. */
public interface UserAcquisitionInsert {

    /** 같은 사용자 행이 이미 있으면 아무것도 바꾸지 않는다. 동시 첫 요청이 와도 한 행만 남는다. */
    void insertIfAbsent(NewAcquisition row);
}
