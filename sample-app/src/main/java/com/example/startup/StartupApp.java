package com.example.startup;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sample app modelled on spring-boot-smoke-test-web-secure-jdbc.
 * <p>
 * On {@link ApplicationReadyEvent} it prints {@code BENCH startup-ms=<jvm uptime>} and
 * exits when {@code -Dbench.exit=true}, so that a JFR recording is dumped on exit.
 */
@SpringBootApplication
public class StartupApp {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(StartupApp.class);
		application.addListeners(new BenchListener());
		application.run(args);
	}

	@RestController
	static class GreetingController {

		private final JdbcTemplate jdbc;

		GreetingController(JdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		@GetMapping("/")
		List<Map<String, Object>> greetings() {
			return this.jdbc.queryForList("select id, message from greeting order by id");
		}

	}

	static class BenchListener implements ApplicationListener<ApplicationReadyEvent> {

		@Override
		public void onApplicationEvent(ApplicationReadyEvent event) {
			long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
			System.out.println("BENCH startup-ms=" + uptime + " spring-ms=" + event.getTimeTaken().toMillis()
					+ " classes=" + ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount());
			if (Boolean.getBoolean("bench.exit")) {
				System.exit(SpringApplication.exit(event.getApplicationContext()));
			}
		}

	}

}
