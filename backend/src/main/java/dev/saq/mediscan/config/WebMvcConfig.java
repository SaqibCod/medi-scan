package dev.saq.mediscan.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC wiring that is not auto-configured.
 *
 * <p>Only one thing so far: registering {@link CurrentOwnerArgumentResolver}, which is what
 * makes {@link CurrentOwner} work on a controller parameter. Without this registration the
 * annotation would be silently ignored and Spring would try to bind {@code OwnerRef} from the
 * request body instead.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	private final CurrentOwnerArgumentResolver currentOwnerResolver;

	public WebMvcConfig(CurrentOwnerArgumentResolver currentOwnerResolver) {
		this.currentOwnerResolver = currentOwnerResolver;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(currentOwnerResolver);
	}
}
