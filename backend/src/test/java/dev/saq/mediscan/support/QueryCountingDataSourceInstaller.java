package dev.saq.mediscan.support;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Wraps the real {@code dataSource} bean in a {@link QueryCountingDataSource} after it is
 * built, rather than declaring a second {@code @Bean} of type {@code DataSource}: Boot's own
 * datasource auto-configuration is {@code @ConditionalOnMissingBean(DataSource.class)}, so a
 * second {@code DataSource} bean definition would make it back off entirely and leave nothing
 * to wrap.
 *
 * <p>A permanent {@code @Component}, not something a test {@code @Import}s - see
 * {@link QueryCountingDataSourceHolder}'s javadoc for why that matters here.
 */
@Component
public class QueryCountingDataSourceInstaller implements BeanPostProcessor {

	private final QueryCountingDataSourceHolder holder;

	public QueryCountingDataSourceInstaller(QueryCountingDataSourceHolder holder) {
		this.holder = holder;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if ("dataSource".equals(beanName) && bean instanceof DataSource ds) {
			QueryCountingDataSource counting = new QueryCountingDataSource(ds);
			holder.set(counting);
			return counting;
		}
		return bean;
	}
}
