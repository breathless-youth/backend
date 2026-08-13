package project.study.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 이 프로젝트의 첫 스케줄러(일일 지표 리포트)를 위해 스케줄링을 켠다.
 *
 * <p>테스트 프로파일에는 Slack webhook URL이 없어 발송기가 비활성이므로,
 * 테스트 실행 중 cron이 발화해도 아무 일도 일어나지 않는다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
