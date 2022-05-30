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

package org.springframework.expression.spel.standard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.expression.spel.InternalParseException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.SpelParseException;

/**
 * Lex some input data into a stream of tokens that can then be parsed.
 *
 * @author Andy Clement
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @since 3.0
 */
class Tokenizer {

	 //替代的操作符
	// If this gets changed, it must remain sorted...
	private static final String[] ALTERNATIVE_OPERATOR_NAMES =
			{"DIV", "EQ", "GE", "GT", "LE", "LT", "MOD", "NE", "NOT"};

	private static final byte[] FLAGS = new byte[256];

	//数字
	private static final byte IS_DIGIT = 0x01;

	//十六进制字符
	private static final byte IS_HEXDIGIT = 0x02;

	//字母
	private static final byte IS_ALPHA = 0x04;

	static {
//		00110000	48	30	'0'
//		00110001	49	31	'1'
//		00110010	50	32	'2'
//		00110011	51	33	'3'
//		00110100	52	34	'4'
//		00110101	53	35	'5'
//		00110110	54	36	'6'
//		00110111	55	37	'7'
//		00111000	56	38	'8'
//		00111001	57	39	'9'
		for (int ch = '0'; ch <= '9'; ch++) {
			//FLAGS[48~57] = 011
			FLAGS[ch] |= IS_DIGIT | IS_HEXDIGIT;
		}
//		01000001	65	41	'A'
//		01000010	66	42	'B'
//		01000011	67	43	'C'
//		01000100	68	44	'D'
//		01000101	69	45	'E'
//		01000110	70	46	'F'
		for (int ch = 'A'; ch <= 'F'; ch++) {
			//FLAGS[65~70] = 010
			FLAGS[ch] |= IS_HEXDIGIT;
		}
//		01100001	97	61	'a'
//		01100010	98	62	'b'
//		01100011	99	63	'c'
//		01100100	100	64	'd'
//		01100101	101	65	'e'
//		01100110	102	66	'f'
		for (int ch = 'a'; ch <= 'f'; ch++) {
			//FLAGS[97~102] = 010
			FLAGS[ch] |= IS_HEXDIGIT;
		}

		for (int ch = 'A'; ch <= 'Z'; ch++) {
			//FLAGS[65~70] = 110
			//FLAGS[71~90] = 100
			FLAGS[ch] |= IS_ALPHA;
		}
		//FLAGS[97~102] = 110
		//FLAGS[103~122] = 100
		for (int ch = 'a'; ch <= 'z'; ch++) {
			FLAGS[ch] |= IS_ALPHA;
		}
	}


	private String expressionString;

	private char[] charsToProcess;

	private int pos;

	private int max;

	private List<Token> tokens = new ArrayList<>();


	public Tokenizer(String inputData) {
		this.expressionString = inputData;
		this.charsToProcess = (inputData + "\0").toCharArray();
		this.max = this.charsToProcess.length;
		this.pos = 0;
	}


	public List<Token> process() {
		while (this.pos < this.max) {
			char ch = this.charsToProcess[this.pos];
			//是字符
			if (isAlphabetic(ch)) {
				//提取一个字符串
				lexIdentifier();
			}
			else {
				switch (ch) {
					case '+':
						if (isTwoCharToken(TokenKind.INC)) {
							pushPairToken(TokenKind.INC);
						}
						else {
							pushCharToken(TokenKind.PLUS);
						}
						break;
					case '_': // the other way to start an identifier
						lexIdentifier();
						break;
					case '-':
						if (isTwoCharToken(TokenKind.DEC)) {
							pushPairToken(TokenKind.DEC);
						}
						else {
							pushCharToken(TokenKind.MINUS);
						}
						break;
					case ':':
						pushCharToken(TokenKind.COLON);
						break;
					case '.':
						pushCharToken(TokenKind.DOT);
						break;
					case ',':
						pushCharToken(TokenKind.COMMA);
						break;
					case '*':
						pushCharToken(TokenKind.STAR);
						break;
					case '/':
						pushCharToken(TokenKind.DIV);
						break;
					case '%':
						pushCharToken(TokenKind.MOD);
						break;
					case '(':
						pushCharToken(TokenKind.LPAREN);
						break;
					case ')':
						pushCharToken(TokenKind.RPAREN);
						break;
					case '[':
						pushCharToken(TokenKind.LSQUARE);
						break;
					case '#':
						pushCharToken(TokenKind.HASH);
						break;
					case ']':
						pushCharToken(TokenKind.RSQUARE);
						break;
					case '{':
						pushCharToken(TokenKind.LCURLY);
						break;
					case '}':
						pushCharToken(TokenKind.RCURLY);
						break;
					case '@':
						pushCharToken(TokenKind.BEAN_REF);
						break;
					case '^':
						if (isTwoCharToken(TokenKind.SELECT_FIRST)) {
							pushPairToken(TokenKind.SELECT_FIRST);
						}
						else {
							//乘幂
							pushCharToken(TokenKind.POWER);
						}
						break;
					case '!':
						//不等于
						if (isTwoCharToken(TokenKind.NE)) {
							pushPairToken(TokenKind.NE);
						}
						else if (isTwoCharToken(TokenKind.PROJECT)) {
							pushPairToken(TokenKind.PROJECT);
						}
						else {
							//非
							pushCharToken(TokenKind.NOT);
						}
						break;
					case '=':
						//相等
						if (isTwoCharToken(TokenKind.EQ)) {
							pushPairToken(TokenKind.EQ);
						}
						else {
							//赋值
							pushCharToken(TokenKind.ASSIGN);
						}
						break;
					case '&':
						// 双与
						if (isTwoCharToken(TokenKind.SYMBOLIC_AND)) {
							pushPairToken(TokenKind.SYMBOLIC_AND);
						}
						else {
							// factory bean 引用
							pushCharToken(TokenKind.FACTORY_BEAN_REF);
						}
						break;
					case '|':
						//不是双或
						if (!isTwoCharToken(TokenKind.SYMBOLIC_OR)) {
							//报错
							raiseParseException(this.pos, SpelMessage.MISSING_CHARACTER, "|");
						}
						pushPairToken(TokenKind.SYMBOLIC_OR);
						break;
					case '?':
						if (isTwoCharToken(TokenKind.SELECT)) {
							pushPairToken(TokenKind.SELECT);
						}
						else if (isTwoCharToken(TokenKind.ELVIS)) {
							pushPairToken(TokenKind.ELVIS);
						}
						else if (isTwoCharToken(TokenKind.SAFE_NAVI)) {
							pushPairToken(TokenKind.SAFE_NAVI);
						}
						else {
							pushCharToken(TokenKind.QMARK);
						}
						break;
					case '$':
						if (isTwoCharToken(TokenKind.SELECT_LAST)) {
							pushPairToken(TokenKind.SELECT_LAST);
						}
						else {
							lexIdentifier();
						}
						break;
					case '>':
						if (isTwoCharToken(TokenKind.GE)) {
							pushPairToken(TokenKind.GE);
						}
						else {
							pushCharToken(TokenKind.GT);
						}
						break;
					case '<':
						if (isTwoCharToken(TokenKind.LE)) {
							pushPairToken(TokenKind.LE);
						}
						else {
							pushCharToken(TokenKind.LT);
						}
						break;
					case '0':
					case '1':
					case '2':
					case '3':
					case '4':
					case '5':
					case '6':
					case '7':
					case '8':
					case '9':
						lexNumericLiteral(ch == '0');
						break;
						//空格直接跳过
					case ' ':
					case '\t':
					case '\r':
					case '\n':
						// drift over white space
						this.pos++;
						break;
					case '\'':
						lexQuotedStringLiteral();
						break;
					case '"':
						lexDoubleQuotedStringLiteral();
						break;
					case 0:
						// hit sentinel at end of value
						this.pos++;  // will take us to the end
						break;
					case '\\':
						raiseParseException(this.pos, SpelMessage.UNEXPECTED_ESCAPE_CHAR);
						break;
					default:
						throw new IllegalStateException("Cannot handle (" + (int) ch + ") '" + ch + "'");
				}
			}
		}
		return this.tokens;
	}


	// STRING_LITERAL: '\''! (APOS|~'\'')* '\''!;
	private void lexQuotedStringLiteral() {
		int start = this.pos;
		boolean terminated = false;
		while (!terminated) {
			this.pos++;
			char ch = this.charsToProcess[this.pos];
			if (ch == '\'') {
				// may not be the end if the char after is also a '
				if (this.charsToProcess[this.pos + 1] == '\'') {
					this.pos++;  // skip over that too, and continue
				}
				else {
					terminated = true;
				}
			}
			if (isExhausted()) {
				raiseParseException(start, SpelMessage.NON_TERMINATING_QUOTED_STRING);
			}
		}
		this.pos++;
		this.tokens.add(new Token(TokenKind.LITERAL_STRING, subarray(start, this.pos), start, this.pos));
	}

	// DQ_STRING_LITERAL: '"'! (~'"')* '"'!;
	private void lexDoubleQuotedStringLiteral() {
		int start = this.pos;
		boolean terminated = false;
		while (!terminated) {
			this.pos++;
			char ch = this.charsToProcess[this.pos];
			if (ch == '"') {
				// may not be the end if the char after is also a "
				if (this.charsToProcess[this.pos + 1] == '"') {
					this.pos++;  // skip over that too, and continue
				}
				else {
					terminated = true;
				}
			}
			if (isExhausted()) {
				raiseParseException(start, SpelMessage.NON_TERMINATING_DOUBLE_QUOTED_STRING);
			}
		}
		this.pos++;
		this.tokens.add(new Token(TokenKind.LITERAL_STRING, subarray(start, this.pos), start, this.pos));
	}

	// REAL_LITERAL :
	// ('.' (DECIMAL_DIGIT)+ (EXPONENT_PART)? (REAL_TYPE_SUFFIX)?) |
	// ((DECIMAL_DIGIT)+ '.' (DECIMAL_DIGIT)+ (EXPONENT_PART)? (REAL_TYPE_SUFFIX)?) |
	// ((DECIMAL_DIGIT)+ (EXPONENT_PART) (REAL_TYPE_SUFFIX)?) |
	// ((DECIMAL_DIGIT)+ (REAL_TYPE_SUFFIX));
	// fragment INTEGER_TYPE_SUFFIX : ( 'L' | 'l' );
	// fragment HEX_DIGIT :
	// '0'|'1'|'2'|'3'|'4'|'5'|'6'|'7'|'8'|'9'|'A'|'B'|'C'|'D'|'E'|'F'|'a'|'b'|'c'|'d'|'e'|'f';
	//
	// fragment EXPONENT_PART : 'e' (SIGN)* (DECIMAL_DIGIT)+ | 'E' (SIGN)*
	// (DECIMAL_DIGIT)+ ;
	// fragment SIGN : '+' | '-' ;
	// fragment REAL_TYPE_SUFFIX : 'F' | 'f' | 'D' | 'd';
	// INTEGER_LITERAL
	// : (DECIMAL_DIGIT)+ (INTEGER_TYPE_SUFFIX)?;

	private void lexNumericLiteral(boolean firstCharIsZero) {
		boolean isReal = false;
		int start = this.pos;
		//后一位字符.
		char ch = this.charsToProcess[this.pos + 1];
		//是否是16进制字符
		boolean isHex = ch == 'x' || ch == 'X';

		// deal with hexadecimal
		//以0x开头
		if (firstCharIsZero && isHex) {
			this.pos = this.pos + 1;
			do {
				//提取0x后面的数字
				this.pos++;
			}
			while (isHexadecimalDigit(this.charsToProcess[this.pos]));
			//是否是l或者L
			if (isChar('L', 'l')) {
				//16进制Long类型常量
				pushHexIntToken(subarray(start + 2, this.pos), true, start, this.pos);
				this.pos++;
			}
			else {
				//16进制int类型常量
				pushHexIntToken(subarray(start + 2, this.pos), false, start, this.pos);
			}
			return;
		}

		// real numbers must have leading digits

		// Consume first part of number
		do {
			//提取数字
			this.pos++;
		}
		while (isDigit(this.charsToProcess[this.pos]));

		// a '.' indicates this number is a real
		ch = this.charsToProcess[this.pos];
		if (ch == '.') {
			isReal = true;
			int dotpos = this.pos;
			// carry on consuming digits
			do {
				//提取小数点后面的数字
				this.pos++;
			}
			while (isDigit(this.charsToProcess[this.pos]));
			//数字末尾紧跟着.且后面不是数字
			if (this.pos == dotpos + 1) {
				// the number is something like '3.'. It is really an int but may be
				// part of something like '3.toString()'. In this case process it as
				// an int and leave the dot as a separate token.
				this.pos = dotpos;
				pushIntToken(subarray(start, this.pos), false, start, this.pos);
				return;
			}
		}

		int endOfNumber = this.pos;

		// Now there may or may not be an exponent

		// Is it a long ?
		if (isChar('L', 'l')) {
			if (isReal) {  // 3.4L - not allowed
				raiseParseException(start, SpelMessage.REAL_CANNOT_BE_LONG);
			}
			pushIntToken(subarray(start, endOfNumber), true, start, endOfNumber);
			this.pos++;
		}
		// 数字e +/- 数字 (f/d) -->科学计数法
		else if (isExponentChar(this.charsToProcess[this.pos])) {
			isReal = true;  // if it wasn't before, it is now
			this.pos++;
			char possibleSign = this.charsToProcess[this.pos];
			if (isSign(possibleSign)) {
				this.pos++;
			}

			// exponent digits
			do {
				this.pos++;
			}
			while (isDigit(this.charsToProcess[this.pos]));
			boolean isFloat = false;
			if (isFloatSuffix(this.charsToProcess[this.pos])) {
				isFloat = true;
				endOfNumber = ++this.pos;
			}
			else if (isDoubleSuffix(this.charsToProcess[this.pos])) {
				endOfNumber = ++this.pos;
			}
			pushRealToken(subarray(start, this.pos), isFloat, start, this.pos);
		}
		else {
			ch = this.charsToProcess[this.pos];
			boolean isFloat = false;
			if (isFloatSuffix(ch)) {
				isReal = true;
				isFloat = true;
				endOfNumber = ++this.pos;
			}
			else if (isDoubleSuffix(ch)) {
				isReal = true;
				endOfNumber = ++this.pos;
			}
			if (isReal) {
				pushRealToken(subarray(start, endOfNumber), isFloat, start, endOfNumber);
			}
			else {
				pushIntToken(subarray(start, endOfNumber), false, start, endOfNumber);
			}
		}
	}

	/**
	 * 词法分析器生成器
	 */
	private void lexIdentifier() {
		int start = this.pos;
		do {
			this.pos++;
		}
		//[a-z,A-Z] |[0-9] | _ | $
		while (isIdentifier(this.charsToProcess[this.pos]));
		//满足条件的字符数组
		char[] subarray = subarray(start, this.pos);

		// Check if this is the alternative (textual) representation of an operator (see
		// alternativeOperatorNames)
		if ((this.pos - start) == 2 || (this.pos - start) == 3) {
			String asString = new String(subarray).toUpperCase();
			int idx = Arrays.binarySearch(ALTERNATIVE_OPERATOR_NAMES, asString);
			if (idx >= 0) {
				pushOneCharOrTwoCharToken(TokenKind.valueOf(asString), start, subarray);
				return;
			}
		}
		this.tokens.add(new Token(TokenKind.IDENTIFIER, subarray, start, this.pos));
	}

	private void pushIntToken(char[] data, boolean isLong, int start, int end) {
		if (isLong) {
			this.tokens.add(new Token(TokenKind.LITERAL_LONG, data, start, end));
		}
		else {
			this.tokens.add(new Token(TokenKind.LITERAL_INT, data, start, end));
		}
	}

	private void pushHexIntToken(char[] data, boolean isLong, int start, int end) {
		if (data.length == 0) {
			if (isLong) {
				raiseParseException(start, SpelMessage.NOT_A_LONG, this.expressionString.substring(start, end + 1));
			}
			else {
				raiseParseException(start, SpelMessage.NOT_AN_INTEGER, this.expressionString.substring(start, end));
			}
		}
		if (isLong) {
			this.tokens.add(new Token(TokenKind.LITERAL_HEXLONG, data, start, end));
		}
		else {
			this.tokens.add(new Token(TokenKind.LITERAL_HEXINT, data, start, end));
		}
	}

	private void pushRealToken(char[] data, boolean isFloat, int start, int end) {
		if (isFloat) {
			this.tokens.add(new Token(TokenKind.LITERAL_REAL_FLOAT, data, start, end));
		}
		else {
			this.tokens.add(new Token(TokenKind.LITERAL_REAL, data, start, end));
		}
	}

	private char[] subarray(int start, int end) {
		return Arrays.copyOfRange(this.charsToProcess, start, end);
	}

	/**
	 * Check if this might be a two character token.
	 */
	private boolean isTwoCharToken(TokenKind kind) {
		return (kind.tokenChars.length == 2 &&
				this.charsToProcess[this.pos] == kind.tokenChars[0] &&
				this.charsToProcess[this.pos + 1] == kind.tokenChars[1]);
	}

	/**
	 * Push a token of just one character in length.
	 */
	private void pushCharToken(TokenKind kind) {
		this.tokens.add(new Token(kind, this.pos, this.pos + 1));
		this.pos++;
	}

	/**
	 * Push a token of two characters in length.
	 */
	private void pushPairToken(TokenKind kind) {
		this.tokens.add(new Token(kind, this.pos, this.pos + 2));
		this.pos += 2;
	}

	private void pushOneCharOrTwoCharToken(TokenKind kind, int pos, char[] data) {
		this.tokens.add(new Token(kind, data, pos, pos + kind.getLength()));
	}

	// ID: ('a'..'z'|'A'..'Z'|'_'|'$') ('a'..'z'|'A'..'Z'|'_'|'$'|'0'..'9'|DOT_ESCAPED)*;
	private boolean isIdentifier(char ch) {
		return isAlphabetic(ch) || isDigit(ch) || ch == '_' || ch == '$';
	}

	private boolean isChar(char a, char b) {
		char ch = this.charsToProcess[this.pos];
		return ch == a || ch == b;
	}

	private boolean isExponentChar(char ch) {
		return ch == 'e' || ch == 'E';
	}

	private boolean isFloatSuffix(char ch) {
		return ch == 'f' || ch == 'F';
	}

	private boolean isDoubleSuffix(char ch) {
		return ch == 'd' || ch == 'D';
	}

	private boolean isSign(char ch) {
		return ch == '+' || ch == '-';
	}

	private boolean isDigit(char ch) {
		if (ch > 255) {
			return false;
		}
		return (FLAGS[ch] & IS_DIGIT) != 0;
	}

	/**
	 * 是否是字母
	 * @param ch
	 * @return
	 */
	private boolean isAlphabetic(char ch) {
		if (ch > 255) {
			return false;
		}
		//二进制第三位是1
		return (FLAGS[ch] & IS_ALPHA) != 0;
	}

	private boolean isHexadecimalDigit(char ch) {
		if (ch > 255) {
			return false;
		}
		return (FLAGS[ch] & IS_HEXDIGIT) != 0;
	}

	private boolean isExhausted() {
		return (this.pos == this.max - 1);
	}

	private void raiseParseException(int start, SpelMessage msg, Object... inserts) {
		throw new InternalParseException(new SpelParseException(this.expressionString, start, msg, inserts));
	}

}
