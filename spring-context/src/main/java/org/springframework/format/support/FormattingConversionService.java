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

package org.springframework.format.support;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.DecoratingProxy;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.format.AnnotationFormatterFactory;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.Parser;
import org.springframework.format.Printer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * A {@link org.springframework.core.convert.ConversionService} implementation
 * designed to be configured as a {@link FormatterRegistry}.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @since 3.0
 *
 * // 继承了GenericConversionService ，所以它能对Converter进行一系列的操作
 * // 实现了接口FormatterRegistry，所以它也可以注册格式化器了
 * // 实现了EmbeddedValueResolverAware，所以它还能有非常强大的功能：处理占位符
 */
public class FormattingConversionService extends GenericConversionService
		implements FormatterRegistry, EmbeddedValueResolverAware {

	@Nullable
	private StringValueResolver embeddedValueResolver;

	private final Map<AnnotationConverterKey, GenericConverter> cachedPrinters = new ConcurrentHashMap<>(64);

	private final Map<AnnotationConverterKey, GenericConverter> cachedParsers = new ConcurrentHashMap<>(64);


	@Override
	public void setEmbeddedValueResolver(StringValueResolver resolver) {
		this.embeddedValueResolver = resolver;
	}


	@Override
	public void addPrinter(Printer<?> printer) {
		Class<?> fieldType = getFieldType(printer, Printer.class);
		addConverter(new PrinterConverter(fieldType, printer, this));
	}

	@Override
	public void addParser(Parser<?> parser) {
		Class<?> fieldType = getFieldType(parser, Parser.class);
		addConverter(new ParserConverter(fieldType, parser, this));
	}

	/**
	 * // 最终也是交给addFormatterForFieldType去做的
	 * 	// getFieldType：它会拿到泛型类型。并且支持DecoratingProxy
	 * @param formatter the formatter to add
	 */
	@Override
	public void addFormatter(Formatter<?> formatter) {
		addFormatterForFieldType(getFieldType(formatter), formatter);
	}

	@Override
	public void addFormatterForFieldType(Class<?> fieldType, Formatter<?> formatter) {
		addConverter(new PrinterConverter(fieldType, formatter, this));
		addConverter(new ParserConverter(fieldType, formatter, this));
	}

	/**
	 * // 存储都是分开存储的  读写分离
	 * 	// PrinterConverter和ParserConverter都是一个GenericConverter  采用内部类实现的
	 * 	// 注意：他们的ConvertiblePair必有一个类型是String.class
	 * 	// Locale一般都可以这么获取：LocaleContextHolder.getLocale()
	 *     // 在进行printer之前，会先判断是否能进行类型转换，如果能进行类型转换会先进行类型转换，之后再格式化
	 * 	// 在parse之后，会判断是否还需要进行类型转换，如果需要类型转换会先进行类型转换
	 * @param fieldType the field type to format
	 * @param printer the printing part of the formatter
	 * @param parser the parsing part of the formatter
	 */
	@Override
	public void addFormatterForFieldType(Class<?> fieldType, Printer<?> printer, Parser<?> parser) {
		addConverter(new PrinterConverter(fieldType, printer, this));
		addConverter(new ParserConverter(fieldType, parser, this));
	}
	/**
	 * 哪怕你是一个AnnotationFormatterFactory，最终也是被适配成了GenericConverter（ConditionalGenericConverter）
	 * @param annotationFormatterFactory
	 * @return
	 */
	@Override
	public void addFormatterForFieldAnnotation(AnnotationFormatterFactory<? extends Annotation> annotationFormatterFactory) {
		// 若你自定义的实现了EmbeddedValueResolverAware接口，还可以使用占位符哟
		// AnnotationFormatterFactory是下面的重点内容
		Class<? extends Annotation> annotationType = getAnnotationType(annotationFormatterFactory);
		if (this.embeddedValueResolver != null && annotationFormatterFactory instanceof EmbeddedValueResolverAware) {
			((EmbeddedValueResolverAware) annotationFormatterFactory).setEmbeddedValueResolver(this.embeddedValueResolver);
		}
		// 对每一种字段的type  都注册一个AnnotationPrinterConverter去处理
		// AnnotationPrinterConverter是一个ConditionalGenericConverter
		// matches方法为：sourceType.hasAnnotation(this.annotationType);
		// 这个判断是呼应的：因为annotationFormatterFactory只会作用在指定的字段类型上的，不符合类型条件的不用添加
		Set<Class<?>> fieldTypes = annotationFormatterFactory.getFieldTypes();
		for (Class<?> fieldType : fieldTypes) {
			addConverter(new AnnotationPrinterConverter(annotationType, annotationFormatterFactory, fieldType));
			addConverter(new AnnotationParserConverter(annotationType, annotationFormatterFactory, fieldType));
		}
	}


	static Class<?> getFieldType(Formatter<?> formatter) {
		return getFieldType(formatter, Formatter.class);
	}

	private static <T> Class<?> getFieldType(T instance, Class<T> genericInterface) {
		Class<?> fieldType = GenericTypeResolver.resolveTypeArgument(instance.getClass(), genericInterface);
		if (fieldType == null && instance instanceof DecoratingProxy) {
			fieldType = GenericTypeResolver.resolveTypeArgument(
					((DecoratingProxy) instance).getDecoratedClass(), genericInterface);
		}
		Assert.notNull(fieldType, () -> "Unable to extract the parameterized field type from " +
					ClassUtils.getShortName(genericInterface) + " [" + instance.getClass().getName() +
					"]; does the class parameterize the <T> generic type?");
		return fieldType;
	}


	@SuppressWarnings("unchecked")
	static Class<? extends Annotation> getAnnotationType(AnnotationFormatterFactory<? extends Annotation> factory) {
		Class<? extends Annotation> annotationType = (Class<? extends Annotation>)
				GenericTypeResolver.resolveTypeArgument(factory.getClass(), AnnotationFormatterFactory.class);
		if (annotationType == null) {
			throw new IllegalArgumentException("Unable to extract parameterized Annotation type argument from " +
					"AnnotationFormatterFactory [" + factory.getClass().getName() +
					"]; does the factory parameterize the <A extends Annotation> generic type?");
		}
		return annotationType;
	}


	private static class PrinterConverter implements GenericConverter {

		private final Class<?> fieldType;

		private final TypeDescriptor printerObjectType;

		@SuppressWarnings("rawtypes")
		private final Printer printer;

		private final ConversionService conversionService;

		public PrinterConverter(Class<?> fieldType, Printer<?> printer, ConversionService conversionService) {
			this.fieldType = fieldType;
			// 会通过解析Printer中的泛型获取具体类型，主要是为了判断是否需要进行类型转换
			this.printerObjectType = TypeDescriptor.valueOf(resolvePrinterObjectType(printer));
			this.printer = printer;
			// 最终也是通过conversionService完成类型转换
			this.conversionService = conversionService;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(new ConvertiblePair(this.fieldType, String.class));
		}

		@Override
		@SuppressWarnings("unchecked")
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			//如果fieldType和Printer<T>泛型类型不一致,先转化
			if (!sourceType.isAssignableTo(this.printerObjectType)) {
				source = this.conversionService.convert(source, sourceType, this.printerObjectType);
			}
			if (source == null) {
				return "";
			}
			return this.printer.print(source, LocaleContextHolder.getLocale());
		}

		@Nullable
		private Class<?> resolvePrinterObjectType(Printer<?> printer) {
			return GenericTypeResolver.resolveTypeArgument(printer.getClass(), Printer.class);
		}

		@Override
		public String toString() {
			return (this.fieldType.getName() + " -> " + String.class.getName() + " : " + this.printer);
		}
	}


	private static class ParserConverter implements GenericConverter {

		private final Class<?> fieldType;

		private final Parser<?> parser;

		private final ConversionService conversionService;

		public ParserConverter(Class<?> fieldType, Parser<?> parser, ConversionService conversionService) {
			this.fieldType = fieldType;
			this.parser = parser;
			this.conversionService = conversionService;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(new ConvertiblePair(String.class, this.fieldType));
		}

		@Override
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			String text = (String) source;
			if (!StringUtils.hasText(text)) {
				return null;
			}
			Object result;
			try {
				result = this.parser.parse(text, LocaleContextHolder.getLocale());
			}
			catch (IllegalArgumentException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new IllegalArgumentException("Parse attempt failed for value [" + text + "]", ex);
			}
			TypeDescriptor resultType = TypeDescriptor.valueOf(result.getClass());
			if (!resultType.isAssignableTo(targetType)) {
				result = this.conversionService.convert(result, resultType, targetType);
			}
			return result;
		}

		@Override
		public String toString() {
			return (String.class.getName() + " -> " + this.fieldType.getName() + ": " + this.parser);
		}
	}


	private class AnnotationPrinterConverter implements ConditionalGenericConverter {

		private final Class<? extends Annotation> annotationType;

		@SuppressWarnings("rawtypes")
		private final AnnotationFormatterFactory annotationFormatterFactory;

		private final Class<?> fieldType;

		public AnnotationPrinterConverter(Class<? extends Annotation> annotationType,
				AnnotationFormatterFactory<?> annotationFormatterFactory, Class<?> fieldType) {

			this.annotationType = annotationType;
			this.annotationFormatterFactory = annotationFormatterFactory;
			this.fieldType = fieldType;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(new ConvertiblePair(this.fieldType, String.class));
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			return sourceType.hasAnnotation(this.annotationType);
		}

		@Override
		@SuppressWarnings("unchecked")
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			Annotation ann = sourceType.getAnnotation(this.annotationType);
			if (ann == null) {
				throw new IllegalStateException(
						"Expected [" + this.annotationType.getName() + "] to be present on " + sourceType);
			}
			AnnotationConverterKey converterKey = new AnnotationConverterKey(ann, sourceType.getObjectType());
			GenericConverter converter = cachedPrinters.get(converterKey);
			if (converter == null) {
				Printer<?> printer = this.annotationFormatterFactory.getPrinter(
						converterKey.getAnnotation(), converterKey.getFieldType());
				converter = new PrinterConverter(this.fieldType, printer, FormattingConversionService.this);
				cachedPrinters.put(converterKey, converter);
			}
			return converter.convert(source, sourceType, targetType);
		}

		@Override
		public String toString() {
			return ("@" + this.annotationType.getName() + " " + this.fieldType.getName() + " -> " +
					String.class.getName() + ": " + this.annotationFormatterFactory);
		}
	}


	private class AnnotationParserConverter implements ConditionalGenericConverter {

		private final Class<? extends Annotation> annotationType;

		@SuppressWarnings("rawtypes")
		private final AnnotationFormatterFactory annotationFormatterFactory;

		private final Class<?> fieldType;

		public AnnotationParserConverter(Class<? extends Annotation> annotationType,
				AnnotationFormatterFactory<?> annotationFormatterFactory, Class<?> fieldType) {

			this.annotationType = annotationType;
			this.annotationFormatterFactory = annotationFormatterFactory;
			this.fieldType = fieldType;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(new ConvertiblePair(String.class, this.fieldType));
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			return targetType.hasAnnotation(this.annotationType);
		}

		@Override
		@SuppressWarnings("unchecked")
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			Annotation ann = targetType.getAnnotation(this.annotationType);
			if (ann == null) {
				throw new IllegalStateException(
						"Expected [" + this.annotationType.getName() + "] to be present on " + targetType);
			}
			AnnotationConverterKey converterKey = new AnnotationConverterKey(ann, targetType.getObjectType());
			GenericConverter converter = cachedParsers.get(converterKey);
			if (converter == null) {
				Parser<?> parser = this.annotationFormatterFactory.getParser(
						converterKey.getAnnotation(), converterKey.getFieldType());
				converter = new ParserConverter(this.fieldType, parser, FormattingConversionService.this);
				cachedParsers.put(converterKey, converter);
			}
			return converter.convert(source, sourceType, targetType);
		}

		@Override
		public String toString() {
			return (String.class.getName() + " -> @" + this.annotationType.getName() + " " +
					this.fieldType.getName() + ": " + this.annotationFormatterFactory);
		}
	}


	private static class AnnotationConverterKey {

		private final Annotation annotation;

		private final Class<?> fieldType;

		public AnnotationConverterKey(Annotation annotation, Class<?> fieldType) {
			this.annotation = annotation;
			this.fieldType = fieldType;
		}

		public Annotation getAnnotation() {
			return this.annotation;
		}

		public Class<?> getFieldType() {
			return this.fieldType;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof AnnotationConverterKey)) {
				return false;
			}
			AnnotationConverterKey otherKey = (AnnotationConverterKey) other;
			return (this.fieldType == otherKey.fieldType && this.annotation.equals(otherKey.annotation));
		}

		@Override
		public int hashCode() {
			return (this.fieldType.hashCode() * 29 + this.annotation.hashCode());
		}
	}

}
