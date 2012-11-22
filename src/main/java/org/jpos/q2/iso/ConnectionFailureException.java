package org.jpos.q2.iso;

/**
 * @author dgrandemange
 *
 */
@SuppressWarnings("serial")
public class ConnectionFailureException extends RuntimeException {

	public ConnectionFailureException() {
		super();
	}

	public ConnectionFailureException(String arg0, Throwable arg1) {
		super(arg0, arg1);
	}

	public ConnectionFailureException(String arg0) {
		super(arg0);
	}

	public ConnectionFailureException(Throwable arg0) {
		super(arg0);
	}

}
