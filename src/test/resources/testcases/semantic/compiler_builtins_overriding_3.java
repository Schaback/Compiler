/* OK */

class System {

}

class Main {
	public Main in;
	public Main out;

	public int read(int x) {
		return x;
	}

	public int println(int x) {
		return x;
	}

	public int write(int x) {
		return x;
	}

	public void flush(int x) {

	}

	public void foo(Main System) {
		int x = System.in.read(0);
		x = System.out.println(x);
		x = System.out.write(x);
		System.out.flush(x);
	}

	public static void main(String[] args) {
		Main System = new Main();
		System.in = new Main();
		System.in.foo(System.in);
	}
}
