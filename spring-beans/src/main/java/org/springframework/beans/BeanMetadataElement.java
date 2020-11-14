/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.beans;

import org.springframework.lang.Nullable;

/**
 * Interface to be implemented by bean metadata elements
 * that carry a configuration source object.
 *
 * @author Juergen Hoeller
 * @since 2.0
 *
 * 具有访问source（配置源）的能力
 * 这个方法在@Configuration中使用较多，因为它会被代理
 *
 * 这个接口提供了一个方法去获取配置源对象，其实就是我们的原文件。
 * 当我们通过注解的方式定义了一个IndexService时，那么此时的IndexService对应的BeanDefinition通过getSource方法返回的就是IndexService.class这个文件对应的一个File对象
 * 如果我们通过@Bean方式定义了一个IndexService的话，那么此时的source是被@Bean注解所标注的一个Mehthod对象。
 *
 * 这个接口提供了一个方法去获取配置源对象，其实就是我们的原文件
 * 当我们通过注解的方式定义了一个IndexService时，那么此时的IndexService对应的BeanDefinition通过getSource方法返回的就是IndexService.class这个文件对应的一个File对象。
 * 如果我们通过@Bean方式定义了一个IndexService的话，那么此时的source是被@Bean注解所标注的一个Mehthod对象。
 */
public interface BeanMetadataElement {

	/**
	 * Return the configuration source {@code Object} for this metadata element
	 * (may be {@code null}).
	 * 接口提供了一个getResource()方法,用来传输一个可配置的源对象
	 * 返回元数据元素配置元对象
	 */
	@Nullable
	default Object getSource() {
		return null;
	}

}
