ALTER table notification
  ADD COLUMN `action_done` TINYINT(1) NOT NULL DEFAULT 0;

ALTER table project_invitation
  ADD COLUMN `applicant_id` INT(11) NOT NULL DEFAULT 0;

ALTER table organization_invitation
  ADD COLUMN `applicant_id` INT(11) NOT NULL DEFAULT 0;