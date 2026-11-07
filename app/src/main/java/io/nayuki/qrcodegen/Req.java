/* Ayudante añadido por FordLauncher: reemplaza java.util.Objects (API 19+) para funcionar en Android 4.1+. */
package io.nayuki.qrcodegen;

final class Req {
	static <T> T nn(T o) { if (o == null) throw new NullPointerException(); return o; }
	private Req() { }
}
