package net.xdob.vexra.protocol;

import java.util.Objects;
import java.util.Optional;

public class BeanTarget<T> {
  private final String className;
  private final String beanName;

  public BeanTarget(String className, String beanName) {
    this.className = className;
    this.beanName = beanName;
  }

	public String getClassName() {
		return className;
	}

	public String getBeanName() {
    return beanName;
  }

  @Override
  public boolean equals(Object object) {
    if (object == null || getClass() != object.getClass()) return false;
    BeanTarget<?> that = (BeanTarget<?>) object;
    return Objects.equals(className, that.className) && Objects.equals(beanName, that.beanName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(className, beanName);
  }

  @Override
  public String toString() {
    return className + "#" + beanName;
  }

  public static <T> BeanTarget<T> valueOf(Class<T> clazz){
    return valueOf(Optional.ofNullable(clazz)
        .map(Class::getName).orElse(null), null);
  }

  public static <T> BeanTarget<T> valueOf(String className, String beanName){
    if(className==null){
      return null;
    }
    return new BeanTarget<T>(className, beanName);
  }
}
