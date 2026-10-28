package project.study.user.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

// 자격 증명은 첫 호출 때 기본 체인에서 읽는다 — 자격 증명이 없는 테스트·로컬에서도 기동은 된다
@Configuration(proxyBeanMethods = false)
public class ProfileImageConfig {

    @Bean
    S3Client profileImageS3Client(@Value("${app.profile-image.region:ap-northeast-2}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean
    S3Presigner profileImageS3Presigner(@Value("${app.profile-image.region:ap-northeast-2}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }
}
