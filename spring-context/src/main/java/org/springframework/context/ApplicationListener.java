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

package org.springframework.context;

import java.util.EventListener;

/**
 * Interface to be implemented by application event listeners.
 *
 * <p>Based on the standard {@code java.util.EventListener} interface
 * for the Observer design pattern.
 *
 * <p>As of Spring 3.0, an {@code ApplicationListener} can generically declare
 * the event type that it is interested in. When registered with a Spring
 * {@code ApplicationContext}, events will be filtered accordingly, with the
 * listener getting invoked for matching event objects only.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @param <E> the specific {@code ApplicationEvent} subclass to listen to
 * @see org.springframework.context.ApplicationEvent
 * @see org.springframework.context.event.ApplicationEventMulticaster
 * @see org.springframework.context.event.EventListener
 *
 * ApplicationListener实现了JDK的EventListener，但它抽象出一个onApplicationEvent方法，使用更方便
 *
 * 事件监听器主要分为两种，一种是我们通过实现接口直接注册到容器中的Bea
 *
 * @Component
 * static class EventListener implements ApplicationListener<MyEvent> {
 *     @Override
 *     public void onApplicationEvent(MyEvent event) {
 *         System.out.println("接收到事件：" + event.getSource());
 *         System.out.println("处理事件....");
 *     }
 * }
 * 另外一个是通过注解的方式
 *
 * @Component
 * static class Listener {
 *     @EventListener  -->EventListenerMethodProcessor
 *     public void listen1(Event event) {
 *         System.out.println("接收到事件1:" + event);
 *         System.out.println("处理事件");
 *     }
 * }
 */
@FunctionalInterface
public interface ApplicationListener<E extends ApplicationEvent> extends EventListener {

	/**
	 * Handle an application event.
	 * @param event the event to respond to
	 */
	void onApplicationEvent(E event);

}
