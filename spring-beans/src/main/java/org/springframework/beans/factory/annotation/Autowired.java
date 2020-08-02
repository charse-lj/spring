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

package org.springframework.beans.factory.annotation;

import java.lang.annotation.*;

/**
 * Marks a constructor, field, setter method, or config method as to be autowired by
 * Spring's dependency injection facilities. This is an alternative to the JSR-330
 * {@link javax.inject.Inject} annotation, adding required-vs-optional semantics.
 *
 * <h3>Autowired Constructors</h3>
 * <p>Only one constructor of any given bean class may declare this annotation with the
 * {@link #required} attribute set to {@code true}, indicating <i>the</i> constructor
 * to autowire when used as a Spring bean. Furthermore, if the {@code required}
 * attribute is set to {@code true}, only a single constructor may be annotated
 * with {@code @Autowired}. If multiple <i>non-required</i> constructors declare the
 * annotation, they will be considered as candidates for autowiring. The constructor
 * with the greatest number of dependencies that can be satisfied by matching beans
 * in the Spring container will be chosen. If none of the candidates can be satisfied,
 * then a primary/default constructor (if present) will be used. Similarly, if a
 * class declares multiple constructors but none of them is annotated with
 * {@code @Autowired}, then a primary/default constructor (if present) will be used.
 * If a class only declares a single constructor to begin with, it will always be used,
 * even if not annotated. An annotated constructor does not have to be public.
 *
 * <h3>Autowired Fields</h3>
 * <p>Fields are injected right after construction of a bean, before any config methods
 * are invoked. Such a config field does not have to be public.
 *
 * <h3>Autowired Methods</h3>
 * <p>Config methods may have an arbitrary name and any number of arguments; each of
 * those arguments will be autowired with a matching bean in the Spring container.
 * Bean property setter methods are effectively just a special case of such a general
 * config method. Such config methods do not have to be public.
 *
 * <h3>Autowired Parameters</h3>
 * <p>Although {@code @Autowired} can technically be declared on individual method
 * or constructor parameters since Spring Framework 5.0, most parts of the
 * framework ignore such declarations. The only part of the core Spring Framework
 * that actively supports autowired parameters is the JUnit Jupiter support in
 * the {@code spring-test} module (see the
 * <a href="https://docs.spring.io/spring/docs/current/spring-framework-reference/testing.html#testcontext-junit-jupiter-di">TestContext framework</a>
 * reference documentation for details).
 *
 * <h3>Multiple Arguments and 'required' Semantics</h3>
 * <p>In the case of a multi-arg constructor or method, the {@link #required} attribute
 * is applicable to all arguments. Individual parameters may be declared as Java-8 style
 * {@link java.util.Optional} or, as of Spring Framework 5.0, also as {@code @Nullable}
 * or a not-null parameter type in Kotlin, overriding the base 'required' semantics.
 *
 * <h3>Autowiring Arrays, Collections, and Maps</h3>
 * <p>In case of an array, {@link java.util.Collection}, or {@link java.util.Map}
 * dependency type, the container autowires all beans matching the declared value
 * type. For such purposes, the map keys must be declared as type {@code String}
 * which will be resolved to the corresponding bean names. Such a container-provided
 * collection will be ordered, taking into account
 * {@link org.springframework.core.Ordered Ordered} and
 * {@link org.springframework.core.annotation.Order @Order} values of the target
 * components, otherwise following their registration order in the container.
 * Alternatively, a single matching target bean may also be a generally typed
 * {@code Collection} or {@code Map} itself, getting injected as such.
 *
 * <h3>Not supported in {@code BeanPostProcessor} or {@code BeanFactoryPostProcessor}</h3>
 * <p>Note that actual injection is performed through a
 * {@link org.springframework.beans.factory.config.BeanPostProcessor
 * BeanPostProcessor} which in turn means that you <em>cannot</em>
 * use {@code @Autowired} to inject references into
 * {@link org.springframework.beans.factory.config.BeanPostProcessor
 * BeanPostProcessor} or
 * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor BeanFactoryPostProcessor}
 * types. Please consult the javadoc for the {@link AutowiredAnnotationBeanPostProcessor}
 * class (which, by default, checks for the presence of this annotation).
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Sam Brannen
 * @since 2.5
 * @see AutowiredAnnotationBeanPostProcessor
 * @see Qualifier
 * @see Value
 *
 * 说个小细节：@Autowired和@Qualifier一起是用时，@Qualifier的值需保证容器里一定有，否则启动报错
 *
 *  准备一个带泛型的Bean
 * @NoArgsConstructor
 * @AllArgsConstructor
 * @Data
 * public class GenericBean<T, W> {
 *
 *     private T t;
 *     private W w;
 *
 * }
 *
 * // config配置文件中注入两个泛型Bean
 *     @Bean
 *     public Parent parentOne() {
 *         return new Parent();
 *     }
 *     @Bean
 *     public Parent parentTwo() {
 *         return new Parent();
 *     }
 *     @Bean
 *     public GenericBean<String, String> stringGeneric() {
 *         return new GenericBean<String, String>("str1", "str2");
 *     }
 *     @Bean
 *     public GenericBean<Object, Object> objectGeneric() {
 *         return new GenericBean<Object, Object>("obj1", 2);
 *     }
 *
 *     使用@Autowired注入，测试一下：
 *     @Autowired
 *     private GenericBean<Object, Object> objectGenericBean; //GenericBean(t=obj1, w=2)
 *     @Autowired
 *     private GenericBean<String, String> stringGenericBean; //GenericBean(t=st   r1, w=str2)
 *     // 注意，容器里虽然有两个Parent，这里即使不使用@Qualifier也不会报错。
 *     // 但是需要注意字段名parentOne，必须是容器里存在的，否则就报错了。
 *     @Autowired
 *     private Parent parentOne; //com.fsx.bean.Parent@23c98163
 *
 *     //Spring4.0后的新特性,这样会注入所有类型为(包括子类)GenericBean的Bean(但是顺序是不确定的,可通过Order接口控制顺序)
 *     @Autowired
 *     private List<GenericBean> genericBeans; //[GenericBean(t=st   r1, w=str2), GenericBean(t=obj1, w=2)]
 *     // 这里的key必须是String类型，把GenericBean类型的都拿出来了，beanName->Bean
 *     @Autowired
 *     private Map<String, GenericBean> genericBeanMap; //{stringGenericBean=GenericBean(t=st   r1, w=str2), objectGenericBean=GenericBean(t=obj1, w=2)}
 *     // 这里面，用上泛型也是好使的，就只会拿指定泛型的了
 *     @Autowired
 *     private Map<String, GenericBean<Object, Object>> genericBeanObjMap; //{objectGenericBean=GenericBean(t=obj1, w=2)}
 *
 *     // 普通类型，容器里面没有的Bean类型，注入是会报错的
 *     //@Autowired
 *     //private Integer normerValue;
 *
 *     泛型依赖注入
 *     @Autowired明明是根据类型进行注入的，那我们往容器里放置了两个GenericBean类型的Bean，为何启动没有报错呢？？？
 *     在上面推荐的博文里已经讲到了，Spring在populateBean这一步为属性赋值的时候，会执行InstantiationAwareBeanPostProcessor处理器的postProcessPropertyValues方法。
 *     这里AutowiredAnnotationBeanPostProcessor该处理器的postProcessPropertyValues方法就是来处理该注解的
 *
 *     @Autowired和@Resource的区别
 *     1.@Autowired根据类型进行注入这话没毛病，但是若没有找到该类型的Bean，若设置了属性required=false也是不会报错的
 *     2.@Autowired注入若找到多个类型的Bean，也不会报错，比如下面三种情况，都不会报错~
 *     // 向容器中注入两个相同类型的Bean，并且都不使用@Primary标注
 *	 @Configuration
 * 	 public class RootConfig {
 *     @Bean
 *     public Parent parentOne() {
 *         return new Parent();
 *     }
 *
 *     @Bean
 *     public Parent parentTwo() {
 *         return new Parent();
 *     }
 *  }
 *
 *   注入方式如下：
 *     @Autowired
 *     private Parent parent;
 *
 *  若什么都不处理，会异常：NoUniqueBeanDefinitionException: No qualifying bean of type 'com.fsx.bean.Parent' available: expected single matching bean but found 2: parentOne,parentTwo
 *
 *  方案一：向容器注入Bean的时候加上@Primary注解（略）
 *  方案二：使用@Qualifier（略）
 *  方案三：使得字段名，和容器里的Bean名称一致，比如改成下面字段名，就不会报错了(它还会根据字段名进行一次过滤，完全找不到再报错)
 *     @Autowired
 *     private Parent parentOne;
 *     @Autowired
 *     private Parent parentTwo;
 *
 *
 *  需要说明的是，它和@Qualifier的区别：他们的生效阶段不一样。
 * 		@Qualifier：它在寻早同类型的Bean的时候就生效了，在方法findAutowireCandidates这里去寻找候选的Bean的时候就生效了，只会找出一个（或者0个出来）
 * 		@Autowired自带的根据字段名匹配：发生在若找出多个同类型Bean的情况下，会根据此字段名称determine一个匹配上的出来
 *
 * @Resource·装配顺序解释：
 *
 * 1.如果既没有指定name，又没有指定type，则自动先按照byName方式进行装配；如果没有匹配，则回退为一个原始类型进行匹配，如果匹配则自动装配
 * 2.如果同时指定了name和type，则从Spring上下文中找到唯一匹配的bean进行装配，找不到则抛出异常
 * 3.如果指定了name，则从上下文中查找名称（id）匹配的bean进行装配，找不到则抛出异常
 * 4.如果指定了type，则从上下文中找到类似匹配的唯一bean进行装配，找不到或是找到多个，都会抛出异常
 * spring4.2之后，@Resource已经全面支持了@Primary以及提供了对@Lazy的支持,全部由CommonAnnotationBeanPostProcessor这个类里面的方法处理
 *
 * 泛型依赖注入的另一优点实例（Base基类设计）
 * // 我定义的BaseDao接口如下：
 * public interface IBaseDBDao<T extends BaseDBEntity<T, PK>, PK extends Number>{ ... }
 *
 * // 定义的BaseService如下：
 * public interface IBaseDBService<T extends BaseDBEntity<T, PK>, PK extends Number> { ... }
 * // 因为service层，所以我肯定更希望的是提供一些基础的、共用的实现，否则抽取的意义就不大了，因此此处就用到了泛型依赖注入：
 *
 * //BaseServiceImpl基础共用实现如下：
 * public abstract class BaseDBServiceImpl<T extends BaseDBEntity<T, PK>, PK extends Number> implements IBaseDBService<T, PK> {
 *
 * 	// 这里就是泛型依赖注入的核心，子类无需再关心dao层的注入，由基类完成dao的注入即可，非常的自动化，切方便管理
 * 	// 这里子类继承，对对应的注入到具体类型的Dao接口代理类，而不用子类关心
 * 	// 如果这是在Spring4之前，我之前做就得提供一个abstract方法给子类，让子类帮我注入给我，我才能书写公用逻辑。
 * 	//然后这一把泛型依赖注入，大大方便了继承者的使用
 * 	// 可以说完全做到了无侵入、赋能的方式加强子类
 *     @Autowired
 *     private IBaseDBDao<T, PK> baseDao;
 *
 * }
 *
 *
 * 冷知识：使用@Value进行依赖注入
 * 其实上面已经提到了，AutowiredAnnotationBeanPostProcessor不仅处理@Autowired也处理@Value，所以向这么写，使用@Value注解也是能够实现依赖注入的
 *
 * @Configuration
 * public class RootConfig {
 *     @Bean
 *     public Person person() {
 *         return new Person();
 *     }
 *
 * 	// 这样就能够实现依赖注入了~~~
 *     @Value("#{person}")
 *     private Person person;
 * }
 * 细节：
 * 1、只能是#{person}而不能是${person}
 * 2、person表示beanName，因此请保证此Bean必须存在。比如若写成这样@Value("#{person2}")就报错
 * @Value结合el表达式也有这样的能力（原理是StandardBeanExpressionResolver和StandardEvaluationContext），只是一般我们不这么干
 *
 * @Value(#{})与@Value(${})的区别
 * @Value(#{})： SpEL表达式
 * @Value("#{}") 表示SpEl表达式通常用来获取bean的属性，或者调用bean的某个方法。当然还有可以表示常量
 * @Value(${})：获取配置文件中的属性值
 */
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Autowired {

	/**
	 * Declares whether the annotated dependency is required.
	 * <p>Defaults to {@code true}.
	 */
	boolean required() default true;

}
