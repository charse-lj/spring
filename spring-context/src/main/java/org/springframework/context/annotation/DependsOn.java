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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Beans on which the current bean depends. Any beans specified are guaranteed to be
 * created by the container before this bean. Used infrequently in cases where a bean
 * does not explicitly depend on another through properties or constructor arguments,
 * but rather depends on the side effects of another bean's initialization.
 *
 * <p>A depends-on declaration can specify both an initialization-time dependency and,
 * in the case of singleton beans only, a corresponding destruction-time dependency.
 * Dependent beans that define a depends-on relationship with a given bean are destroyed
 * first, prior to the given bean itself being destroyed. Thus, a depends-on declaration
 * can also control shutdown order.
 *
 * <p>May be used on any class directly or indirectly annotated with
 * {@link org.springframework.stereotype.Component} or on methods annotated
 * with {@link Bean}.
 *
 * <p>Using {@link DependsOn} at the class level has no effect unless component-scanning
 * is being used. If a {@link DependsOn}-annotated class is declared via XML,
 * {@link DependsOn} annotation metadata is ignored, and
 * {@code <bean depends-on="..."/>} is respected instead.
 *
 * @author Juergen Hoeller
 * @since 3.0
 *
 * 为什么要控制Bean的加载顺序？
 * @Order注解等并不能控制Bean的加载顺序的~~因为你如果熟悉原理了就知道Spring在解析Bean的时候，根本就没有参考这个注解
 * 另外@Configuration配置类的加载，也不会受到@Order注解的影响。因为之前源码解释过，它拿到配置的数组，仅仅就是一个for循环遍历去解析了
 * 但是但Spring能保证如果A依赖B(如beanA中有@Autowired B的变量)，那么B将先于A被加载（这属于Spring容器内部就自动识别处理了）。但如果beanA不直接依赖B，我们如何让B仍先加载?
 *
 * 需要的场景距离如下
 * 1.bean A 间接（并不是直接@Autowired）依赖 bean B。如bean A有一个属性，需要在初始化的时候对其进行赋值（需要在初始化的时候做，是因为这个属性其实是包装了其它的几个Bean的，比如说代理了Bean B），所以这就形成了Bean A间接的依赖Bean B了
 * 2.bean A是事件发布者（或JMS发布者），bean B (或一些) 负责监听这些事件，典型的如观察者模式。我们不想B 错过任何事件，那么B需要首先被初始化。
 *
 * 
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DependsOn {

	String[] value() default {};

}
