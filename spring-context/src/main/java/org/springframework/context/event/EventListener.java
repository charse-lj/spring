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

package org.springframework.context.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.ApplicationEvent;
import org.springframework.core.annotation.AliasFor;

/**
 * Annotation that marks a method as a listener for application events.
 *
 * <p>If an annotated method supports a single event type, the method may
 * declare a single parameter that reflects the event type to listen to.
 * If an annotated method supports multiple event types, this annotation
 * may refer to one or more supported event types using the {@code classes}
 * attribute. See the {@link #classes} javadoc for further details.
 *
 * <p>Events can be {@link ApplicationEvent} instances as well as arbitrary
 * objects.
 *
 * <p>Processing of {@code @EventListener} annotations is performed via
 * the internal {@link EventListenerMethodProcessor} bean which gets
 * registered automatically when using Java config or manually via the
 * {@code <context:annotation-config/>} or {@code <context:component-scan/>}
 * element when using XML config.
 *
 * <p>Annotated methods may have a non-{@code void} return type. When they
 * do, the result of the method invocation is sent as a new event. If the
 * return type is either an array or a collection, each element is sent
 * as a new individual event.
 *
 * <p>This annotation may be used as a <em>meta-annotation</em> to create custom
 * <em>composed annotations</em>.
 *
 * <h3>Exception Handling</h3>
 * <p>While it is possible for an event listener to declare that it
 * throws arbitrary exception types, any checked exceptions thrown
 * from an event listener will be wrapped in an
 * {@link java.lang.reflect.UndeclaredThrowableException UndeclaredThrowableException}
 * since the event publisher can only handle runtime exceptions.
 *
 * <h3>Asynchronous Listeners</h3>
 * <p>If you want a particular listener to process events asynchronously, you
 * can use Spring's {@link org.springframework.scheduling.annotation.Async @Async}
 * support, but be aware of the following limitations when using asynchronous events.
 *
 * <ul>
 * <li>If an asynchronous event listener throws an exception, it is not propagated
 * to the caller. See {@link org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
 * AsyncUncaughtExceptionHandler} for more details.</li>
 * <li>Asynchronous event listener methods cannot publish a subsequent event by returning a
 * value. If you need to publish another event as the result of the processing, inject an
 * {@link org.springframework.context.ApplicationEventPublisher ApplicationEventPublisher}
 * to publish the event manually.</li>
 * </ul>
 *
 * <h3>Ordering Listeners</h3>
 * <p>It is also possible to define the order in which listeners for a
 * certain event are to be invoked. To do so, add Spring's common
 * {@link org.springframework.core.annotation.Order @Order} annotation
 * alongside this event listener annotation.
 *
 * @author Stephane Nicoll
 * @author Sam Brannen
 * @since 4.2
 * @see EventListenerMethodProcessor
 *
 * 在任意方法上标注@EventListener注解，指定 classes，即需要处理的事件类型，一般就是 ApplicationEven 及其子类（当然任意事件也是Ok的，比如下面的MyAppEvent就是个普通的POJO），可以设置多项
 * public class MyAppEvent {
 *
 *     private String name;
 *
 *     public MyAppEvent(String name) {
 *         this.name = name;
 *     }
 * }
 *
 * // 显然此处，它会收到两个时间，分别进行处理
 * @Component
 * public class MyAllEventListener {
 *
 *     //value必须给值,但可以不用是ApplicationEvent的子类  任意事件都ok
 *     // 也可以给一个入参，代表事件的Event
 *     @EventListener(value = {ContextRefreshedEvent.class, MyAppEvent.class}
 *             // confition的使用，若同一个事件进行区分同步异步 等等条件的可以使用此confition 支持spel表达式  非常强大
 *             ,condition = "#event.isAsync == false")
 *      public void handle(Object o){
 *           System.out.println(o);
 *           System.out.println("事件来了~");
 *          }
 *   }
 *   方式更被推崇，因为它是方法级别的，更轻便了
 *
 *   @EventListener的使用注意事项 ：Caused by: java.lang.IllegalStateException: Need to invoke method 'applicationContextEvent' declared on target class 'HelloServiceImpl', but not found in any interface(s) of the exposed proxy type. Either pull the method up to an interface or switch to CGLIB proxies by enforcing proxy-target-class mode in your configuration.
 *   那是因为：你把@EventListener写在XXXImpl实现类里面了，形如这样
 * @Slf4j
 * @Service
 * public class HelloServiceImpl implements HelloService {
 * 	...
 *     private ApplicationContext applicationContext;
 *     @EventListener(classes = ContextRefreshedEvent.class)
 *     public void applicationContextEvent(ContextRefreshedEvent event) {
 *         applicationContext = event.getApplicationContext();
 *     }
 *     ...
 * }
 * 根本原因：Spring在解析标注有此注解的方法的时候是这么解析的：Method methodToUse = AopUtils.selectInvocableMethod(method, context.getType(beanName));
 * 这里的context.getType(beanName)就是问题的关键，因为Spring默认给我们使用的是JDK Proxy代理（此处只考虑被代理的情况，我相信没有人的应用不使用代理的吧），所以此处getType拿到的默认就是个Proxy，显然它是它是找不到我们对应方法的（因为方法在impl的实现类里，接口里可以木有）
 *
 * 另外有一个小细节：标注有@EventListener注解（包括@TransactionalEventListener）的方法的访问权限最低是protected的
 * 另外可以在监听方法上标注@Order来控制执行顺序哦，一般人我不告诉他~
 *
 * 知道了原因，从来都不缺解决方案：
 * 1.强制使用CGLIB动态代理机制
 * 2.监听器（@EventListener）单独写在一个@Compnent里。当然你可以使用内部类没关系，如下也是ok的，若需要高内聚小姑的话可以这么写：
 *
 * @Slf4j
 * @Service
 * public class HelloServiceImpl implements HelloService {
 * 	...
 * 	// 这里用private是木有关系的  需要注意的是若你使用内部类，建议务必是static的  否则可能报错如下：
 * 	// Caused by: org.springframework.beans.factory.BeanNotOfRequiredTypeException: Bean named 'helloServiceImpl' is expected to be of type 'com.fsx.service.HelloServiceImpl' but was actually of type 'com.sun.proxy.$Proxy35'
 * 	// 因为static的类初始化不依赖于外部类，而非static得依赖外部类（所以若不是CGLIB代理  一样出问题）
 *     @Component
 *     private static class MyListener {
 * 	    private ApplicationContext applicationContext;
 *         @EventListener(classes = ContextRefreshedEvent.class)
 *         public void applicationContextEvent(ContextRefreshedEvent event) {
 *             applicationContext = event.getApplicationContext();
 *         }
 *     }
 * 	...
 * }
 *
 * 使用中的小细节
 * 1.@EventListener注解用在接口或者父类上都是没有任何问题的（这样子类就不用再写了，在接口层进行控制）
 * 2.@EventListener标注的方法，无视访问权限
 * 3.AbstractApplicationEventMulticaster的相关方法比如addApplicationListenerBean、removeApplicationListener。。。都是线程安全的。
 * 4.若想要异步执行事件，请自己配置@Bean这个Bean。然后setTaskExecutor()一个进去
 *
 * ApplicationListener和@EventListener的区别
 * @EventListener存在漏事件的现象，但是ApplicationListener能监听到所有的相关事件
 * 1.ApplicationListener的注册时机
 * 	它是靠一个后置处理器：ApplicationListenerDetector它来处理的。它有两个方法处理
 * 	因为它是以Bean定义的形式注册进工厂的，并且refresh()中有一步registerListeners()它负责注册所有的监听器（Bean形式的），然后才是finishBeanFactoryInitialization(beanFactory)，所以它是不会落掉事件的
 * 2. @EventListener的注册时机
 * 	注册它的是EventListenerMethodProcessor，它是一个SmartInitializingSingleton，它一直到preInstantiateSingletons()所有的单例Bean全部实例化完成了之后，它才被统一注册进去。所以它注册的时机是挺晚的。
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})  //只能标注在方法上  和当作元注解使用
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventListener {

	/**
	 * Alias for {@link #classes}.
	 */
	@AliasFor("classes")
	Class<?>[] value() default {};

	/**
	 * The event classes that this listener handles.
	 * <p>If this attribute is specified with a single value, the
	 * annotated method may optionally accept a single parameter.
	 * However, if this attribute is specified with multiple values,
	 * the annotated method must <em>not</em> declare any parameters.
	 */
	@AliasFor("value")
	Class<?>[] classes() default {};

	/**
	 * Spring Expression Language (SpEL) expression used for making the event
	 * handling conditional.
	 * <p>The event will be handled if the expression evaluates to boolean
	 * {@code true} or one of the following strings: {@code "true"}, {@code "on"},
	 * {@code "yes"}, or {@code "1"}.
	 * <p>The default expression is {@code ""}, meaning the event is always handled.
	 * <p>The SpEL expression will be evaluated against a dedicated context that
	 * provides the following metadata:
	 * <ul>
	 * <li>{@code #root.event} or {@code event} for references to the
	 * {@link ApplicationEvent}</li>
	 * <li>{@code #root.args} or {@code args} for references to the method
	 * arguments array</li>
	 * <li>Method arguments can be accessed by index. For example, the first
	 * argument can be accessed via {@code #root.args[0]}, {@code args[0]},
	 * {@code #a0}, or {@code #p0}.</li>
	 * <li>Method arguments can be accessed by name (with a preceding hash tag)
	 * if parameter names are available in the compiled byte code.</li>
	 * </ul>
	 *
	 *  则个条件大多数使用者都非常的默认，毕竟绝大多数情况下都是不需要使用的~~~
	 * 	总体上，它是根据条件，判断此handler是否需要处理这事件  更加细粒度的控制  支持SpEL表达值
	 * 	内置的#root.event表示当前事件，#root.args当前方法的入参（数组形式）
	 */
	String condition() default "";

}
