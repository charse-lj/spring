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

package org.springframework.context.event;

import java.lang.reflect.Method;

import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Default {@link EventListenerFactory} implementation that supports the
 * regular {@link EventListener} annotation.
 *
 * <p>Used as "catch-all" implementation by default.
 *
 * @author Stephane Nicoll
 * @since 4.2
 */
public class DefaultEventListenerFactory implements EventListenerFactory, Ordered {

	//它希望自己是被最后执行的~~~
	private int order = LOWEST_PRECEDENCE;


	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}


	//匹配所有的标注了@EventListener 的方法
	@Override
	public boolean supportsMethod(Method method) {
		return true;
	}

	// ApplicationListenerMethodAdapter是一个通用的方法监听适配器~~~~
	@Override
	public ApplicationListener<?> createApplicationListener(String beanName, Class<?> type, Method method) {
		return new ApplicationListenerMethodAdapter(beanName, type, method);
	}

}
