package server.repositories.group.project;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import server.entities.dto.User;
import server.entities.dto.group.project.Project;
import server.repositories.group.GroupRepository;

import java.util.List;


@Transactional
public interface ProjectRepository extends GroupRepository<Project> {
  @Query("From Project t WHERE t.owner =:owner AND t.name=:name")
  List<Project> getGroups(@Param("owner") User user, @Param("name") String name);
}