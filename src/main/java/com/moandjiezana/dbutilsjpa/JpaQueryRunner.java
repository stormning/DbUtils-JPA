package com.moandjiezana.dbutilsjpa;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;

import org.apache.commons.dbutils.BasicRowProcessor;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.RowProcessor;
import org.apache.commons.dbutils.handlers.BeanHandler;
import org.apache.commons.dbutils.handlers.ScalarHandler;

import com.moandjiezana.dbutilsjpa.internal.PropertyDescriptorWrapper;

/**
 * Provides a JPA-friendly interface to the underlying QueryRunner.
 *
 * Immutable and thread-safe.
*/
public class JpaQueryRunner {

  public static final ScalarHandler<Long> DEFAULT_GENERATED_KEYS_HANDLER = new ScalarHandler<Long>();
  public static final SqlWriter DEFAULT_SQL_WRITER = new SqlWriter();
  public static final BasicRowProcessor DEFAULT_ROW_PROCESSOR = new BasicRowProcessor(new JpaBeanProcessor());
  public static final NewEntityTester DEFAULT_ENTITY_TESTER = new NewEntityTester() {
    @Override
    public boolean isNew(Object entity) {
      AccessibleObject idAccessor = Entities.getIdAccessor(entity.getClass());
      try {
        if (idAccessor instanceof Field) {
          Field field = (Field) idAccessor;
          field.setAccessible(true);

          return field.get(entity) == null;
        } else {
          return ((Method) idAccessor).invoke(entity) == null;
        }
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      } catch (InvocationTargetException e) {
        throw new RuntimeException(e.getCause());
      }
    }
  };

  private final QueryRunner queryRunner;
  private final SqlWriter sqlWriter;
  private final NewEntityTester entityTester;
  private final RowProcessor rowProcessor;
  private final ResultSetHandler<?> generatedKeysHandler;
  
  public static class Builder {
    
    private SqlWriter sqlWriter;
    private NewEntityTester entityTester;
    private ResultSetHandler<?> generatedKeysHandler;
    private RowProcessor rowProcessor;

    public JpaQueryRunner build(QueryRunner queryRunner) {
      return new JpaQueryRunner(queryRunner, choose(this.sqlWriter, DEFAULT_SQL_WRITER), choose(this.entityTester, DEFAULT_ENTITY_TESTER), choose(this.rowProcessor, DEFAULT_ROW_PROCESSOR), choose(this.generatedKeysHandler, DEFAULT_GENERATED_KEYS_HANDLER));
    }
    
    public Builder sqlWriter(SqlWriter sqlWriter) {
      this.sqlWriter = sqlWriter;
      return this;
    }
    
    public Builder entityTester(NewEntityTester entityTester) {
      this.entityTester = entityTester;
      return this;
    }
    
    public Builder generatedKeysHandler(ResultSetHandler<?> generatedKeysHandler) {
      this.generatedKeysHandler = generatedKeysHandler;
      return this;
    }
    
    public Builder rowProcessor(RowProcessor rowProcessor) {
      this.rowProcessor = rowProcessor;
      return this;
    }
    
    private <T> T choose(T value, T fallback) {
      return value != null ? value : fallback;
    }
  }

  public JpaQueryRunner(QueryRunner queryRunner) {
    this(queryRunner, DEFAULT_SQL_WRITER, DEFAULT_ENTITY_TESTER, DEFAULT_ROW_PROCESSOR, DEFAULT_GENERATED_KEYS_HANDLER);
  }

  public JpaQueryRunner(QueryRunner queryRunner, SqlWriter sqlWriter, NewEntityTester entityTester,
      RowProcessor rowProcessor, ResultSetHandler<?> generatedKeysHandler) {
    this.queryRunner = queryRunner;
    this.sqlWriter = sqlWriter;
    this.entityTester = entityTester;
    this.rowProcessor = rowProcessor;
    this.generatedKeysHandler = generatedKeysHandler;
  }

  /**
   * Find by primary key. Search for an entity of the specified class and
   * primary key. If the entity instance is contained in the persistence
   * context, it is returned from there.
   * 
   * @param entityClass
   *          entity class
   * @param primaryKey
   *          primary key
   * @return the found entity instance or null if the entity does not exist
   * @throws IllegalArgumentException
   *           if the first argument does not denote an entity type or the
   *           second argument is is not a valid type for that entity&apos;s
   *           primary key or is null
   */
  public <T> T query(Class<T> entityClass, Object primaryKey) {
    try {
      return entityClass.cast(queryRunner.query(sqlWriter.selectById(entityClass, primaryKey), new BeanHandler<T>(
          entityClass, rowProcessor), primaryKey));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Insert if new, update if already exists.
   * 
   * @param entity
   * @return The number of rows updated
   */
  public int save(Object entity) {
    try {
      Class<? extends Object> entityClass = entity.getClass();
      boolean isNew = entityTester.isNew(entity);

      AccessibleObject idAccessor = Entities.getIdAccessor(entityClass);
      List<PropertyDescriptorWrapper> pD = new ArrayList<PropertyDescriptorWrapper>();
      PropertyDescriptorWrapper idPropertyDescriptor = null;
      PropertyDescriptorWrapper[] propertyDescriptorWrappers = null;
      if (idAccessor instanceof Method) {
        PropertyDescriptor[] propertyDescriptors = Introspector.getBeanInfo(entityClass).getPropertyDescriptors();
        propertyDescriptorWrappers = new PropertyDescriptorWrapper[propertyDescriptors.length];
        for (int i = 0; i < propertyDescriptorWrappers.length; i++) {
          propertyDescriptorWrappers[i] = new PropertyDescriptorWrapper(propertyDescriptors[i]);
        }
      } else {
        propertyDescriptorWrappers = new PropertyDescriptorWrapper[entityClass.getDeclaredFields().length];
        for (int i = 0; i < propertyDescriptorWrappers.length; i++) {
          Field field = entityClass.getDeclaredFields()[i];
          propertyDescriptorWrappers[i] = new PropertyDescriptorWrapper(Entities.getName(entityClass.getDeclaredFields()[i]), field);
        }
      }
      
      for (PropertyDescriptorWrapper propertyDescriptorWrapper : propertyDescriptorWrappers) {
        AccessibleObject accessibleObject = propertyDescriptorWrapper.getAccessibleObject();
        Member member = propertyDescriptorWrapper.getMember();
        
        if (!Entities.isMapped(member.getDeclaringClass()) || Entities.isTransient(accessibleObject)
            || Entities.isRelation(accessibleObject) || Modifier.isStatic(member.getModifiers())) {
          continue;
        }

        if (Entities.isIdAccessor(accessibleObject)) {
          idPropertyDescriptor = propertyDescriptorWrapper;
          continue;
        }
        
        if (isNew && accessibleObject.isAnnotationPresent(Column.class) && !accessibleObject.getAnnotation(Column.class).insertable()) {
          continue;
        } else if (!isNew && accessibleObject.isAnnotationPresent(Column.class) && !accessibleObject.getAnnotation(Column.class).updatable()) {
          continue;
        }
        
        pD.add(propertyDescriptorWrapper);
      }
      if (!isNew) {
        for (PropertyDescriptorWrapper propertyDescriptorWrapper : propertyDescriptorWrappers) {
          if (Entities.isIdAccessor(propertyDescriptorWrapper.getAccessibleObject())) {
            pD.add(propertyDescriptorWrapper);
          }
        }
      }

      Object[] args = new Object[pD.size()];
      for (int i = 0; i < args.length; i++) {
        PropertyDescriptorWrapper propertyDescriptor = pD.get(i);
          args[i] = propertyDescriptor.get(entity);
        if (args[i] != null && Enum.class.isAssignableFrom(args[i].getClass())) {
          args[i] = args[i].toString();
        }
      }

      if (isNew) {
        Object newId = queryRunner.insert(sqlWriter.insert(entityClass), generatedKeysHandler, args);
        if (idPropertyDescriptor != null) {
          idPropertyDescriptor.set(entity, newId);
        }
        
        return 1;
      } else {
        return queryRunner.update(sqlWriter.updateById(entityClass), args);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    } catch (IntrospectionException e) {
      throw new RuntimeException(e);
    }
  }

  public int delete(Class<?> entityClass, Object primaryKey) {
    try {
      return queryRunner.update(sqlWriter.deleteById(entityClass), primaryKey);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
