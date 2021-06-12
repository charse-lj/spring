/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.core.annotation.AliasFor;
import org.springframework.core.type.filter.TypeFilter;

/**
 * Configures component scanning directives for use with @{@link Configuration} classes.
 * Provides support parallel with Spring XML's {@code <context:component-scan>} element.
 *
 * <p>Either {@link #basePackageClasses} or {@link #basePackages} (or its alias
 * {@link #value}) may be specified to define specific packages to scan. If specific
 * packages are not defined, scanning will occur from the package of the
 * class that declares this annotation.
 *
 * <p>Note that the {@code <context:component-scan>} element has an
 * {@code annotation-config} attribute; however, this annotation does not. This is because
 * in almost all cases when using {@code @ComponentScan}, default annotation config
 * processing (e.g. processing {@code @Autowired} and friends) is assumed. Furthermore,
 * when using {@link AnnotationConfigApplicationContext}, annotation config processors are
 * always registered, meaning that any attempt to disable them at the
 * {@code @ComponentScan} level would be ignored.
 *
 * <p>See {@link Configuration @Configuration}'s Javadoc for usage examples.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @see Configuration
 *
 * @ComponentScan是不会扫描jar包的
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Repeatable(ComponentScans.class)
public @interface ComponentScan {

	/**
	 * Alias for {@link #basePackages}.
	 * <p>Allows for more concise annotation declarations if no other attributes
	 * are needed &mdash; for example, {@code @ComponentScan("org.my.pkg")}
	 * instead of {@code @ComponentScan(basePackages = "org.my.pkg")}.
	 *
	 * 用于指定包的路径，进行扫描
	 */
	@AliasFor("basePackages")
	String[] value() default {};

	/**
	 * Base packages to scan for annotated components.
	 * <p>{@link #value} is an alias for (and mutually exclusive with) this
	 * attribute.
	 * <p>Use {@link #basePackageClasses} for a type-safe alternative to
	 * String-based package names.
	 *
	 * 用于指定包的路径，进行扫描
	 */
	@AliasFor("value")
	String[] basePackages() default {};

	/**
	 * Type-safe alternative to {@link #basePackages} for specifying the packages
	 * to scan for annotated components. The package of each class specified will be scanned.
	 * <p>Consider creating a special no-op marker class or interface in each package
	 * that serves no purpose other than being referenced by this attribute.
	 *
	 * 用于指定某个类的包的路径进行扫描
	 */
	Class<?>[] basePackageClasses() default {};

	/**
	 * The {@link BeanNameGenerator} class to be used for naming detected components
	 * within the Spring container.
	 * <p>The default value of the {@link BeanNameGenerator} interface itself indicates
	 * that the scanner used to process this {@code @ComponentScan} annotation should
	 * use its inherited bean name generator, e.g. the default
	 * {@link AnnotationBeanNameGenerator} or any custom instance supplied to the
	 * application context at bootstrap time.
	 * @see AnnotationConfigApplicationContext#setBeanNameGenerator(BeanNameGenerator)
	 * @see AnnotationBeanNameGenerator
	 * @see FullyQualifiedAnnotationBeanNameGenerator
	 *
	 *  bean的名称的生成器
	 */
	Class<? extends BeanNameGenerator> nameGenerator() default BeanNameGenerator.class;

	/**
	 * The {@link ScopeMetadataResolver} to be used for resolving the scope of detected components.
	 * 用于解析@Scope注解
	 */
	Class<? extends ScopeMetadataResolver> scopeResolver() default AnnotationScopeMetadataResolver.class;

	/**
	 * Indicates whether proxies should be generated for detected components, which may be
	 * necessary when using scopes in a proxy-style fashion.
	 * <p>The default is defer to the default behavior of the component scanner used to
	 * execute the actual scan.
	 * <p>Note that setting this attribute overrides any value set for {@link #scopeResolver}.
	 * @see ClassPathBeanDefinitionScanner#setScopedProxyMode(ScopedProxyMode)
	 *
	 * 用来设置类的代理模式
	 */
	ScopedProxyMode scopedProxy() default ScopedProxyMode.DEFAULT;

	/**
	 * Controls the class files eligible for component detection.
	 * <p>Consider use of {@link #includeFilters} and {@link #excludeFilters}
	 * for a more flexible approach.
	 *
	 * 扫描路径
	 */
	String resourcePattern() default ClassPathScanningCandidateComponentProvider.DEFAULT_RESOURCE_PATTERN;

	/**
	 * Indicates whether automatic detection of classes annotated with {@code @Component}
	 * {@code @Repository}, {@code @Service}, or {@code @Controller} should be enabled.
	 * 指定是否需要使用Spring默认的扫描规则
	 * 是否开启对@Component，@Repository，@Service，@Controller的类进行检测
	 */
	boolean useDefaultFilters() default true;

	/**
	 * Specifies which types are eligible for component scanning.
	 * <p>Further narrows the set of candidate components from everything in {@link #basePackages}
	 * to everything in the base packages that matches the given filter or filters.
	 * <p>Note that these filters will be applied in addition to the default filters, if specified.
	 * Any type under the specified base packages which matches a given filter will be included,
	 * even if it does not match the default filters (i.e. is not annotated with {@code @Component}).
	 * @see #resourcePattern()
	 * @see #useDefaultFilters()
	 * 
	 *  useDefaultFilters=false,加上includeFilters我们就可以只扫描指定的组件了
	 * 对被扫描的包或类进行过滤，若符合条件，不论组件上是否有注解，Bean对象都将被创建
	 *  @ComponentScan(value = "spring.annotation.componentscan",includeFilters = {
	 *     @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = {Controller.class, Service.class}),
	 *     @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {SchoolDao.class}),
	 *     @ComponentScan.Filter(type = FilterType.CUSTOM, classes = {MyTypeFilter.class}),
	 *     @ComponentScan.Filter(type = FilterType.ASPECTJ, pattern = "spring.annotation..*"),
	 *     @ComponentScan.Filter(type = FilterType.REGEX, pattern = "^[A-Za-z.]+Dao$")
	 *  },useDefaultFilters = false)
	 *  useDefaultFilters 必须设为 false
	 */
	Filter[] includeFilters() default {};

	/**
	 * 指定需要排除的组件
	 * Specifies which types are not eligible for component scanning.
	 * @see #resourcePattern
	 */
	Filter[] excludeFilters() default {};

	/**
	 * Specify whether scanned beans should be registered for lazy initialization.
	 * <p>Default is {@code false}; switch this to {@code true} when desired.
	 * @since 4.1
	 *
	 * 指定是否应注册扫描的Bean以进行延迟初始化
	 */
	boolean lazyInit() default false;


	/**
	 * Declares the type filter to be used as an {@linkplain ComponentScan#includeFilters
	 * include filter} or {@linkplain ComponentScan#excludeFilters exclude filter}.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({})
	@interface Filter {

		/**
		 * The type of filter to use.
		 * <p>Default is {@link FilterType#ANNOTATION}.
		 * @see #classes
		 * @see #pattern
		 *
		 * 要使用的过滤器类型，默认为 ANNOTATION 注解类型
		 */
		FilterType type() default FilterType.ANNOTATION;

		/**
		 * Alias for {@link #classes}.
		 * @see #classes
		 *
		 * 指定在该规则下过滤的表达式
		 * 过滤器的参数，参数必须为class数组，单个参数可以不加大括号
		 * 只能用于 ANNOTATION 、ASSIGNABLE_TYPE 、CUSTOM 这三个类型
		 * @ComponentScan.Filter(type = FilterType.ANNOTATION, value = {Controller.class, Service.class})
		 * @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {SchoolDao.class})
		 * @ComponentScan.Filter(type = FilterType.CUSTOM, classes = {MyTypeFilter.class})
		 */
		@AliasFor("classes")
		Class<?>[] value() default {};

		/**
		 * The class or classes to use as the filter.
		 * <p>The following table explains how the classes will be interpreted
		 * based on the configured value of the {@link #type} attribute.
		 * <table border="1">
		 * <tr><th>{@code FilterType}</th><th>Class Interpreted As</th></tr>
		 * <tr><td>{@link FilterType#ANNOTATION ANNOTATION}</td>
		 * <td>the annotation itself</td></tr>
		 * <tr><td>{@link FilterType#ASSIGNABLE_TYPE ASSIGNABLE_TYPE}</td>
		 * <td>the type that detected components should be assignable to</td></tr>
		 * <tr><td>{@link FilterType#CUSTOM CUSTOM}</td>
		 * <td>an implementation of {@link TypeFilter}</td></tr>
		 * </table>
		 * <p>When multiple classes are specified, <em>OR</em> logic is applied
		 * &mdash; for example, "include types annotated with {@code @Foo} OR {@code @Bar}".
		 * <p>Custom {@link TypeFilter TypeFilters} may optionally implement any of the
		 * following {@link org.springframework.beans.factory.Aware Aware} interfaces, and
		 * their respective methods will be called prior to {@link TypeFilter#match match}:
		 * <ul>
		 * <li>{@link org.springframework.context.EnvironmentAware EnvironmentAware}</li>
		 * <li>{@link org.springframework.beans.factory.BeanFactoryAware BeanFactoryAware}
		 * <li>{@link org.springframework.beans.factory.BeanClassLoaderAware BeanClassLoaderAware}
		 * <li>{@link org.springframework.context.ResourceLoaderAware ResourceLoaderAware}
		 * </ul>
		 * <p>Specifying zero classes is permitted but will have no effect on component
		 * scanning.
		 * @since 4.2
		 * @see #value
		 * @see #type
		 *
		 * 作用同上面的 value 相同
		 * ANNOTATION 参数为注解类，如  Controller.class, Service.class, Repository.class
		 * ASSIGNABLE_TYPE 参数为类，如 SchoolDao.class
		 * CUSTOM  参数为实现 TypeFilter 接口的类 ，如 MyTypeFilter.class
		 * MyTypeFilter 同时还能实现 EnvironmentAware，BeanFactoryAware，BeanClassLoaderAware，ResourceLoaderAware 这四个接口
		 * EnvironmentAware
		 * 此方法用来接收 Environment 数据 ，主要为程序的运行环境，Environment 接口继承自 PropertyResolver 接口，详细内容在下方
		 * @Override
		 * public void setEnvironment(Environment environment) {
		 *    String property = environment.getProperty("os.name");
		 * }
		 *
		 * BeanFactoryAware
		 * BeanFactory Bean容器的根接口，用于操作容器，如获取bean的别名、类型、实例、是否单例的数据
		 * @Override
		 * public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		 *     Object bean = beanFactory.getBean("BeanName")
		 * }
		 *
		 * BeanClassLoaderAware
		 * ClassLoader 是类加载器，在此方法里只能获取资源和设置加载器状态
		 * @Override
		 * public void setBeanClassLoader(ClassLoader classLoader) {
		 *     ClassLoader parent = classLoader.getParent();
		 * }
		 *
		 * ResourceLoaderAware
		 * ResourceLoader 用于获取类加载器和根据路径获取资源
		 * public void setResourceLoader(ResourceLoader resourceLoader) {
		 *     ClassLoader classLoader = resourceLoader.getClassLoader();
		 * }
		 */
		@AliasFor("value")
		Class<?>[] classes() default {};

		/**
		 * The pattern (or patterns) to use for the filter, as an alternative
		 * to specifying a Class {@link #value}.
		 * <p>If {@link #type} is set to {@link FilterType#ASPECTJ ASPECTJ},
		 * this is an AspectJ type pattern expression. If {@link #type} is
		 * set to {@link FilterType#REGEX REGEX}, this is a regex pattern
		 * for the fully-qualified class names to match.
		 * @see #type
		 *
		 * @see #classes
		 */
		String[] pattern() default {};

	}

}
