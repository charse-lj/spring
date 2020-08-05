/*
 * Copyright 2002-2012 the original author or authors.
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

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;

/**
 * Base class for events raised for an {@code ApplicationContext}.
 *
 * @author Juergen Hoeller
 * @since 2.5
 *
 * ContextRefreshedEvent：容器初始化完成刷新时触发。此时所有的Bean已经初始化完成、后置处理器等都已经完成
 * ContextStartedEvent：AbstractApplicationContext#strart()被调用时。 需要手动调用，个人觉得没啥卵用
 * ContextStoppedEvent：容器的stop方法被手动调用时。 也没啥卵用
 * ContextClosedEvent：close() 关闭容器时候发布。一个已关闭的上下文到达生命周期末端；它不能被刷新或重启
 * 
 */
@SuppressWarnings("serial")
public abstract class ApplicationContextEvent extends ApplicationEvent {

	/**
	 * Create a new ContextStartedEvent.
	 * @param source the {@code ApplicationContext} that the event is raised for
	 * (must not be {@code null})
	 */
	public ApplicationContextEvent(ApplicationContext source) {
		super(source);
	}

	/**
	 * Get the {@code ApplicationContext} that the event was raised for.
	 */
	public final ApplicationContext getApplicationContext() {
		return (ApplicationContext) getSource();
	}

}
