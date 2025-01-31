/*
 * Copyright 2012-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bind.PropertiesConfigurationFactory;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.env.EnumerableCompositePropertySource;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertySourcesLoader;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;

/**
 * {@link EnvironmentPostProcessor} that configures the context environment by loading
 * properties from well known file locations. By default properties will be loaded from
 * 'application.properties' and/or 'application.yml' files in the following locations:
 * <ul>
 * <li>classpath:</li>
 * <li>file:./</li>
 * <li>classpath:config/</li>
 * <li>file:./config/:</li>
 * </ul>
 * <p>
 * Alternative search locations and names can be specified using
 * {@link #setSearchLocations(String)} and {@link #setSearchNames(String)}.
 * <p>
 * Additional files will also be loaded based on active profiles. For example if a 'web'
 * profile is active 'application-web.properties' and 'application-web.yml' will be
 * considered.
 * <p>
 * The 'spring.config.name' property can be used to specify an alternative name to load
 * and the 'spring.config.location' property can be used to specify alternative search
 * locations or specific files.
 * <p>
 * Configuration properties are also bound to the {@link SpringApplication}. This makes it
 * possible to set {@link SpringApplication} properties dynamically, like the sources
 * ("spring.main.sources" - a CSV list) the flag to indicate a web environment
 * ("spring.main.web_environment=true") or the flag to switch off the banner
 * ("spring.main.show_banner=false").
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 */
public class ConfigFileEnvironmentPostProcessor implements EnvironmentPostProcessor,
		ApplicationListener<ApplicationPreparedEvent>, Ordered {

	private static final String DEFAULT_PROPERTIES = "defaultProperties";

	// Note the order is from least to most specific (last one wins)
	private static final String DEFAULT_SEARCH_LOCATIONS = "classpath:/,classpath:/config/,file:./,file:./config/";

	private static final String DEFAULT_NAMES = "application";

	/**
	 * The "active profiles" property name.
	 */
	public static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";

	/**
	 * The "includes profiles" property name.
	 */
	public static final String INCLUDE_PROFILES_PROPERTY = "spring.profiles.include";

	/**
	 * The "config name" property name.
	 */
	public static final String CONFIG_NAME_PROPERTY = "spring.config.name";

	/**
	 * The "config location" property name.
	 */
	public static final String CONFIG_LOCATION_PROPERTY = "spring.config.location";

	/**
	 * The default order for the processor.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	private final DeferredLog logger = new DeferredLog();

	private String searchLocations;

	private String names;

	private int order = DEFAULT_ORDER;

	private final ConversionService conversionService = new DefaultConversionService();

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment,
			SpringApplication application) {
		addPropertySources(environment, application.getResourceLoader());
		bindToSpringApplication(environment, application);
	}

	@Override
	public void onApplicationEvent(ApplicationPreparedEvent event) {
		this.logger.replayTo(ConfigFileEnvironmentPostProcessor.class);
		addPostProcessors(event.getApplicationContext());
	}

	/**
	 * Add config file property sources to the specified environment.
	 * @param environment the environment to add source to
	 * @param resourceLoader the resource loader
	 * @see #addPostProcessors(ConfigurableApplicationContext)
	 */
	protected void addPropertySources(ConfigurableEnvironment environment,
			ResourceLoader resourceLoader) {
		RandomValuePropertySource.addToEnvironment(environment);
		try {
			new Loader(environment, resourceLoader).load();
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to load configuration files", ex);
		}
	}

	/**
	 * Bind the environment to the {@link SpringApplication}.
	 * @param environment the environment to bind
	 * @param application the application to bind to
	 */
	protected void bindToSpringApplication(ConfigurableEnvironment environment,
			SpringApplication application) {
		PropertiesConfigurationFactory<SpringApplication> binder = new PropertiesConfigurationFactory<SpringApplication>(
				application);
		binder.setTargetName("spring.main");
		binder.setConversionService(this.conversionService);
		binder.setPropertySources(environment.getPropertySources());
		try {
			binder.bindPropertiesToTarget();
		}
		catch (BindException ex) {
			throw new IllegalStateException("Cannot bind to SpringApplication", ex);
		}
	}

	/**
	 * Add appropriate post-processors to post-configure the property-sources.
	 * @param context the context to configure
	 */
	protected void addPostProcessors(ConfigurableApplicationContext context) {
		context.addBeanFactoryPostProcessor(
				new PropertySourceOrderingPostProcessor(context));
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Set the search locations that will be considered as a comma-separated list. Each
	 * search location should be a directory path (ending in "/") and it will be prefixed
	 * by the file names constructed from {@link #setSearchNames(String) search names} and
	 * profiles (if any) plus file extensions supported by the properties loaders.
	 * Locations are considered in the order specified, with later items taking precedence
	 * (like a map merge).
	 * @param locations the search locations
	 */
	public void setSearchLocations(String locations) {
		Assert.hasLength(locations, "Locations must not be empty");
		this.searchLocations = locations;
	}

	/**
	 * Sets the names of the files that should be loaded (excluding file extension) as a
	 * comma-separated list.
	 * @param names the names to load
	 */
	public void setSearchNames(String names) {
		Assert.hasLength(names, "Names must not be empty");
		this.names = names;
	}

	/**
	 * {@link BeanFactoryPostProcessor} to re-order our property sources below any
	 * {@code @PropertySource} items added by the {@link ConfigurationClassPostProcessor}.
	 */
	private class PropertySourceOrderingPostProcessor
			implements BeanFactoryPostProcessor, Ordered {

		private ConfigurableApplicationContext context;

		PropertySourceOrderingPostProcessor(ConfigurableApplicationContext context) {
			this.context = context;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
				throws BeansException {
			reorderSources(this.context.getEnvironment());
		}

		private void reorderSources(ConfigurableEnvironment environment) {
			ConfigurationPropertySources
					.finishAndRelocate(environment.getPropertySources());
			PropertySource<?> defaultProperties = environment.getPropertySources()
					.remove(DEFAULT_PROPERTIES);
			if (defaultProperties != null) {
				environment.getPropertySources().addLast(defaultProperties);
			}
		}

	}

	/**
	 * Loads candidate property sources and configures the active profiles.
	 */
	private class Loader {

		private final Log logger = ConfigFileEnvironmentPostProcessor.this.logger;

		private final ConfigurableEnvironment environment;

		private final ResourceLoader resourceLoader;

		private PropertySourcesLoader propertiesLoader;

		private Queue<String> profiles;

		private List<String> processedProfiles;

		private boolean activatedProfiles;

		Loader(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
			this.environment = environment;
			this.resourceLoader = resourceLoader == null ? new DefaultResourceLoader()
					: resourceLoader;
		}

		public void load() throws IOException {
			this.propertiesLoader = new PropertySourcesLoader();
			this.profiles = Collections.asLifoQueue(new LinkedList<String>());
			this.processedProfiles = new LinkedList<String>();
			this.activatedProfiles = false;
			if (this.environment.containsProperty(ACTIVE_PROFILES_PROPERTY)) {
				// Any pre-existing active profiles set via property sources (e.g. System
				// properties) take precedence over those added in config files.
				maybeActivateProfiles(
						this.environment.getProperty(ACTIVE_PROFILES_PROPERTY));
			}
			else {
				// Pre-existing active profiles set via Environment.setActiveProfiles()
				// are additional profiles and config files are allowed to add more if
				// they want to, so don't call addActiveProfiles() here.
				List<String> list = new ArrayList<String>(
						Arrays.asList(this.environment.getActiveProfiles()));
				// Reverse them so the order is the same as from getProfilesForValue()
				// (last one wins when properties are eventually resolved)
				Collections.reverse(list);
				this.profiles.addAll(list);
			}

			if (this.profiles.isEmpty()) {
				for (String defaultProfile : this.environment.getDefaultProfiles()) {
					if (!this.profiles.contains(defaultProfile)) {
						this.profiles.add(defaultProfile);
					}
				}
			}

			// The default profile for these purposes is represented as null. We add it
			// last so that it is first out of the queue (active profiles will then
			// override any settings in the defaults when the list is reversed later).
			this.profiles.add(null);

			while (!this.profiles.isEmpty()) {
				String profile = this.profiles.poll();
				for (String location : getSearchLocations()) {
					if (!location.endsWith("/")) {
						// location is a filename already, so don't search for more
						// filenames
						load(location, null, profile);
					}
					else {
						for (String name : getSearchNames()) {
							load(location, name, profile);
						}
					}
				}
				this.processedProfiles.add(profile);
			}

			addConfigurationProperties(this.propertiesLoader.getPropertySources());
		}

		private void load(String location, String name, String profile)
				throws IOException {
			String group = "profile=" + (profile == null ? "" : profile);
			if (!StringUtils.hasText(name)) {
				// Try to load directly from the location
				loadIntoGroup(group, location, profile);
			}
			else {
				// Search for a file with the given name
				for (String ext : this.propertiesLoader.getAllFileExtensions()) {
					if (profile != null) {
						// Try the profile specific file
						loadIntoGroup(group, location + name + "-" + profile + "." + ext,
								null);
						for (String processedProfile : this.processedProfiles) {
							if (processedProfile != null) {
								loadIntoGroup(group, location + name + "-"
										+ processedProfile + "." + ext, profile);
							}
						}
						// Sometimes people put "spring.profiles: dev" in
						// application-dev.yml (gh-340). Arguably we should try and error
						// out on that, but we can be kind and load it anyway.
						loadIntoGroup(group, location + name + "-" + profile + "." + ext,
								profile);
					}
					// Also try the profile specific section (if any) of the normal file
					loadIntoGroup(group, location + name + "." + ext, profile);
				}
			}
		}

		private PropertySource<?> loadIntoGroup(String identifier, String location,
				String profile) throws IOException {
			Resource resource = this.resourceLoader.getResource(location);
			PropertySource<?> propertySource = null;
			StringBuilder msg = new StringBuilder();
			if (resource != null && resource.exists()) {
				String name = "applicationConfig: [" + location + "]";
				String group = "applicationConfig: [" + identifier + "]";
				propertySource = this.propertiesLoader.load(resource, group, name,
						profile);
				if (propertySource != null) {
					msg.append("Loaded ");
					maybeActivateProfiles(
							propertySource.getProperty(ACTIVE_PROFILES_PROPERTY));
					addIncludeProfiles(
							propertySource.getProperty(INCLUDE_PROFILES_PROPERTY));
				}
				else {
					msg.append("Skipped (empty) ");
				}
			}
			else {
				msg.append("Skipped ");
			}
			msg.append("config file ");
			msg.append("'").append(location).append("'");
			if (StringUtils.hasLength(profile)) {
				msg.append(" for profile ").append(profile);
			}
			if (resource == null || !resource.exists()) {
				msg.append(" resource not found");
				this.logger.trace(msg);
			}
			else {
				this.logger.debug(msg);
			}
			return propertySource;
		}

		private void maybeActivateProfiles(Object value) {
			if (this.activatedProfiles) {
				if (value != null) {
					this.logger.debug("Profiles already activated, '" + value
							+ "' will not be applied");
				}
				return;
			}
			Set<String> profiles = getProfilesForValue(value);
			activateProfiles(profiles);
			if (profiles.size() > 0) {
				this.logger.debug("Activated profiles "
						+ StringUtils.collectionToCommaDelimitedString(profiles));
				this.activatedProfiles = true;
			}
		}

		private void addIncludeProfiles(Object value) {
			Set<String> profiles = getProfilesForValue(value);
			activateProfiles(profiles);
		}

		private Set<String> getProfilesForValue(Object property) {
			return asResolvedSet((property == null ? null : property.toString()), null);
		}

		private void activateProfiles(Set<String> profiles) {
			for (String profile : profiles) {
				this.profiles.add(profile);
				if (!this.environment.acceptsProfiles(profile)) {
					// If it's already accepted we assume the order was set
					// intentionally
					prependProfile(this.environment, profile);
				}
			}
		}

		private void prependProfile(ConfigurableEnvironment environment, String profile) {
			Set<String> profiles = new LinkedHashSet<String>();
			environment.getActiveProfiles(); // ensure they are initialized
			// But this one should go first (last wins in a property key clash)
			profiles.add(profile);
			profiles.addAll(Arrays.asList(environment.getActiveProfiles()));
			environment.setActiveProfiles(profiles.toArray(new String[profiles.size()]));
		}

		private Set<String> getSearchLocations() {
			Set<String> locations = new LinkedHashSet<String>();
			// User-configured settings take precedence, so we do them first
			if (this.environment.containsProperty(CONFIG_LOCATION_PROPERTY)) {
				for (String path : asResolvedSet(
						this.environment.getProperty(CONFIG_LOCATION_PROPERTY), null)) {
					if (!path.contains("$")) {
						path = StringUtils.cleanPath(path);
						if (!ResourceUtils.isUrl(path)) {
							path = ResourceUtils.FILE_URL_PREFIX + path;
						}
					}
					locations.add(path);
				}
			}
			locations.addAll(
					asResolvedSet(ConfigFileEnvironmentPostProcessor.this.searchLocations,
							DEFAULT_SEARCH_LOCATIONS));
			return locations;
		}

		private Set<String> getSearchNames() {
			if (this.environment.containsProperty(CONFIG_NAME_PROPERTY)) {
				return asResolvedSet(this.environment.getProperty(CONFIG_NAME_PROPERTY),
						null);
			}
			return asResolvedSet(ConfigFileEnvironmentPostProcessor.this.names,
					DEFAULT_NAMES);
		}

		private Set<String> asResolvedSet(String value, String fallback) {
			List<String> list = Arrays
					.asList(StringUtils.commaDelimitedListToStringArray(value != null
							? this.environment.resolvePlaceholders(value) : fallback));
			Collections.reverse(list);
			return new LinkedHashSet<String>(list);
		}

		private void addConfigurationProperties(MutablePropertySources sources) {
			List<PropertySource<?>> reorderedSources = new ArrayList<PropertySource<?>>();
			for (PropertySource<?> item : sources) {
				reorderedSources.add(item);
			}
			// Maybe we should add before the DEFAULT_PROPERTIES if it exists?
			this.environment.getPropertySources()
					.addLast(new ConfigurationPropertySources(reorderedSources));
		}

	}

	/**
	 * Holds the configuration {@link PropertySource}s as they are loaded can relocate
	 * them once configuration classes have been processed.
	 */
	static class ConfigurationPropertySources
			extends EnumerablePropertySource<Collection<PropertySource<?>>> {

		private static final String NAME = "applicationConfigurationProperties";

		private final Collection<PropertySource<?>> sources;

		private final String[] names;

		ConfigurationPropertySources(Collection<PropertySource<?>> sources) {
			super(NAME, sources);
			this.sources = sources;
			List<String> names = new ArrayList<String>();
			for (PropertySource<?> source : sources) {
				if (source instanceof EnumerablePropertySource) {
					names.addAll(Arrays.asList(
							((EnumerablePropertySource<?>) source).getPropertyNames()));
				}
			}
			this.names = names.toArray(new String[names.size()]);
		}

		@Override
		public Object getProperty(String name) {
			for (PropertySource<?> propertySource : this.sources) {
				Object value = propertySource.getProperty(name);
				if (value != null) {
					return value;
				}
			}
			return null;
		}

		public static void finishAndRelocate(MutablePropertySources propertySources) {
			ConfigurationPropertySources removed = (ConfigurationPropertySources) propertySources
					.get(ConfigurationPropertySources.NAME);
			String name = ConfigurationPropertySources.NAME;
			if (removed != null) {
				for (PropertySource<?> propertySource : removed.sources) {
					if (propertySource instanceof EnumerableCompositePropertySource) {
						EnumerableCompositePropertySource composite = (EnumerableCompositePropertySource) propertySource;
						for (PropertySource<?> nested : composite.getSource()) {
							propertySources.addAfter(name, nested);
							name = nested.getName();
						}
					}
					else {
						propertySources.addAfter(name, propertySource);
					}
				}
				propertySources.remove(ConfigurationPropertySources.NAME);
			}
		}

		@Override
		public String[] getPropertyNames() {
			return this.names;
		}

	}

}
