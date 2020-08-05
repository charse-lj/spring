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

package org.springframework.transaction.event;

import java.lang.reflect.Method;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * {@link EventListenerFactory} implementation that handles {@link TransactionalEventListener}
 * annotated methods.
 *
 * @author Stephane Nicoll
 * @since 4.2
 *
 * 它是一个和注解驱动的声明式事务相关的监听器工厂。用于处理@TransactionalEventListener这个注解标注的方法
 */
public class TransactionalEventListenerFactory implements EventListenerFactory, Ordered {

	private int order = 50;


	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}


	// 很显然，它要求此方法必须标注@TransactionalEventListener这个注解
	// 备注：@TransactionalEventListener继承自@EventListener
	@Override
	public boolean supportsMethod(Method method) {
		return AnnotatedElementUtils.hasAnnotation(method, TransactionalEventListener.class);
	}

	// ApplicationListenerMethodTransactionalAdapter这个是适配事务监听方法的适配器
	// 它继承自：ApplicationListenerMethodAdapter
	@Override
	public ApplicationListener<?> createApplicationListener(String beanName, Class<?> type, Method method) {
		return new ApplicationListenerMethodTransactionalAdapter(beanName, type, method);
	}

}
