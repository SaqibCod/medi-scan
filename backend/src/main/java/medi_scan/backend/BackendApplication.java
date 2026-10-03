package medi_scan.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Medi-Scan backend.
 *
 * <p>{@code @EnableScheduling} drives the retention cleanup job (see {@code docs/dataflow.md}
 * section 9.2). {@code @ConfigurationPropertiesScan} picks up the {@code mediscan.*} records
 * so configuration stays typed rather than scattered {@code @Value} lookups.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
