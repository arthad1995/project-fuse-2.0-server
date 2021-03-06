package server.repositories.group;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;
import server.entities.dto.group.Group;
import server.entities.dto.group.GroupApplication;
import server.entities.dto.user.User;

import java.util.List;

@NoRepositoryBean
public interface GroupApplicantRepository<T extends GroupApplication, W extends Group> extends CrudRepository<T, Long> {
  List<T> getApplicants(@Param("group") W group, @Param("status") String status);

  List<T> getApplicantsBySender(@Param("sender") User user);

  List<T> getApplicantsBySenderAndStatus(@Param("sender") User user, @Param("status") String status);

  List<T> getApplicantsByStatus(@Param("status") String status);
}
