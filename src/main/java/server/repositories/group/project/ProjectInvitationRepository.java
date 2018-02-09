package server.repositories.group.project;

import org.springframework.data.repository.CrudRepository;
import server.entities.dto.user.User;
import server.entities.dto.group.project.ProjectInvitation;
import server.repositories.group.GroupInvitationRepository;

import java.util.List;

public interface ProjectInvitationRepository extends GroupInvitationRepository<ProjectInvitation> {
  List<ProjectInvitation> findByReceiver(User receiver);
}
