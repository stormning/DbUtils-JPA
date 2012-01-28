package com.moandjiezana.dbutilsjpa;

import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.util.Date;

import org.apache.commons.dbutils.QueryRunner;
import org.fest.reflect.core.Reflection;
import org.fest.reflect.field.Invoker;
import org.junit.Test;
import org.mockito.Mockito;

import com.moandjiezana.dbutilsjpa.testutils.CustomNamePropertyEntity;
import com.moandjiezana.dbutilsjpa.testutils.NonUpdatableEntity;
import com.moandjiezana.dbutilsjpa.testutils.SimpleEntity;

public class JpaQueryRunnerTest {

  private QueryRunner queryRunner = Mockito.mock(QueryRunner.class);
  private JpaQueryRunner runner = new JpaQueryRunner(queryRunner);
  
  @Test
  public void should_update_if_entity_has_id() throws SQLException {
    SimpleEntity entity = new SimpleEntity();
    Reflection.field("id").ofType(Long.class).in(entity).set(Long.valueOf(1));
    Invoker<String> name = Reflection.field("name").ofType(String.class).in(entity);
    name.set("my name");
    
    runner.save(entity);
    
    verify(queryRunner).update("UPDATE SimpleEntity SET name=? WHERE id=?", name.get(), entity.getId());
  }
  
  @Test
  public void should_update_from_annotated_properties_if_entity_has_id() throws SQLException {
    CustomNamePropertyEntity entity = new CustomNamePropertyEntity();
    entity.setId(Long.valueOf(1));
    entity.setDate(new Date());
    
    runner.save(entity);
    
    verify(queryRunner).update("UPDATE myTableName SET customDateColumn=? WHERE customNameId=?", entity.getDate(), entity.getId());
  }
  
  @Test
  public void should_ignore_non_updatable_column() throws SQLException {
    NonUpdatableEntity entity = new NonUpdatableEntity();
    entity.setId(1L);
    entity.setNotInserted("inserted");
    
    runner.save(entity);
    
    verify(queryRunner).update("UPDATE NonUpdatableEntity SET name=?, notInserted=? WHERE id=?", entity.getName(), entity.getNotInserted(), entity.getId());
  }
  
  @Test
  public void should_insert_new_entity() throws SQLException {
    SimpleEntity entity = new SimpleEntity();
    Invoker<String> name = Reflection.field("name").ofType(String.class).in(entity);
    name.set("a name");
    
    runner.save(entity);
    
    verify(queryRunner).update("INSERT INTO SimpleEntity(name) VALUES(?)", new Object[] { name.get() });
  }
  
  @Test
  public void should_ignore_non_insertable_column() throws SQLException {
    NonUpdatableEntity entity = new NonUpdatableEntity();
    
    runner.save(entity);
    
    verify(queryRunner).update("INSERT INTO NonUpdatableEntity(name,notUpdated) VALUES(?,?)", entity.getName(), entity.getNotUpdated());
  }
}
