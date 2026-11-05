# O agendamento diário. Nasce DESLIGADO (var.agendamento_ativo = false): ligue depois de conferir o custo do Fargate e de
# preencher os parâmetros do SSM.
resource "aws_scheduler_schedule" "diario" {
  name                = "${var.nome}-diario"
  schedule_expression = var.agendamento
  state               = var.agendamento_ativo ? "ENABLED" : "DISABLED"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_ecs_cluster.auditor.arn
    role_arn = aws_iam_role.agendador.arn

    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.job.arn
      launch_type         = "FARGATE"
      task_count          = 1

      network_configuration {
        subnets          = var.subnet_ids
        security_groups  = [aws_security_group.task.id]
        assign_public_ip = var.ip_publico
      }
    }

    retry_policy {
      maximum_retry_attempts       = 2
      maximum_event_age_in_seconds = 3600
    }
  }
}
