/*
 * Copyright 2002-2018 the original author or authors.
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

/**
 * A {@link Condition} that offers more fine-grained control when used with
 * {@code @Configuration}. Allows certain {@link Condition Conditions} to adapt when they match
 * based on the configuration phase. For example, a condition that checks if a bean
 * has already been registered might choose to only be evaluated during the
 * {@link ConfigurationPhase#REGISTER_BEAN REGISTER_BEAN} {@link ConfigurationPhase}.
 *
 * @author Phillip Webb
 * @since 4.0
 * @see Configuration
 */
public interface ConfigurationCondition extends Condition {

	/**
	 * Return the {@link ConfigurationPhase} in which the condition should be evaluated.
	 */
	ConfigurationPhase getConfigurationPhase();


	/**
	 * The various configuration phases where the condition could be evaluated.
	 * ConfigurationPhase的作用就是根据条件来判断是否加载这个配置类，OnBeanCondition（此注解的功能就是判断是否存在某个bean，如果存在，则不注入标注的bean或者类）之所以返回REGISTER_BEAN，是因为需要无论如何都要加载这个配置类（如果是PARSE_CONFIGURATION，则有可能不加载），配置类中的bean的注入需要再根据bean的注入条件来判断
	 * 控制的是过滤的时机，是在创建Configuration类的时候过滤还是在创建bean的时候过滤
	 */
	enum ConfigurationPhase {

		/**
		 * The {@link Condition} should be evaluated as a {@code @Configuration}
		 * class is being parsed.
		 * <p>If the condition does not match at this point, the {@code @Configuration}
		 * class will not be added.
		 *
		 * @Configuration注解的类解析的阶段判断Condition
		 * 如果Condition不匹配，则@Configuration注解的类不会加载
		 */
		PARSE_CONFIGURATION,

		/**
		 * The {@link Condition} should be evaluated when adding a regular
		 * (non {@code @Configuration}) bean. The condition will not prevent
		 * {@code @Configuration} classes from being added.
		 * <p>At the time that the condition is evaluated, all {@code @Configuration}s
		 * will have been parsed.
		 *
		 * @Configuration注解的类实例化为bean的阶段判断Condition
		 * 无论Condition是否匹配，@Configuration注解的类都会加载，且类加载先于Condition判断
		 */
		REGISTER_BEAN
	}

}
