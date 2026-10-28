package project.study.acquisition.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.acquisition.dto.AcquisitionRequest;
import project.study.acquisition.dto.AcquisitionResponse;
import project.study.acquisition.referrer.Attribution;
import project.study.acquisition.referrer.InstallReferrerClassifier;
import project.study.acquisition.repository.NewAcquisition;
import project.study.acquisition.repository.UserAcquisitionRepository;

@Service
@RequiredArgsConstructor
public class AcquisitionService {

    private final InstallReferrerClassifier classifier;
    private final UserAcquisitionRepository repository;

    /**
     * 처음 온 값만 저장하고, 이미 있으면 저장된 값을 그대로 돌려준다. 앱은 응답으로 첫 값을 확인하므로 재요청이 안전하다.
     */
    @Transactional
    public AcquisitionResponse record(Long userId, AcquisitionRequest request) {
        String installReferrer = blankToNull(request.installReferrer());
        String deepLinkAd = blankToNull(request.deepLinkAd());
        Attribution attribution = classifier.classify(installReferrer, deepLinkAd);
        Instant capturedAt = request.capturedAt().toInstant();
        repository.insertIfAbsent(
                new NewAcquisition(userId, request.platform(), installReferrer, deepLinkAd, capturedAt, attribution));
        return AcquisitionResponse.from(repository.findById(userId).orElseThrow());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
