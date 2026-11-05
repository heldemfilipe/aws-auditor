resource "aws_ecs_cluster" "auditor" {
  name = var.nome
}

resource "aws_cloudwatch_log_group" "auditor" {
  name              = "/ecs/${var.nome}"
  retention_in_days = var.retencao_dos_logs_em_dias
}

# Sem entrada: o job só SAI para a internet (a AWS e o Telegram, por HTTPS)
resource "aws_security_group" "task" {
  name        = "${var.nome}-task"
  description = "Auditor: sem entrada; saida so por HTTPS"
  vpc_id      = var.vpc_id

  egress {
    description = "HTTPS para as APIs da AWS e o Telegram"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_ecs_task_definition" "job" {
  family                   = var.nome
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memoria
  execution_role_arn       = aws_iam_role.execucao.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = "auditor"
      image     = var.imagem
      essential = true

      # Modo job: uma auditoria e sai (0 = ok, 1 = falhou). Sem servidor web.
      environment = [
        { name = "AUDITOR_JOB_HABILITADO", value = "true" },
        { name = "SPRING_MAIN_WEB_APPLICATION_TYPE", value = "none" },
        { name = "AUDITOR_AWS_REGIOES", value = join(",", var.regioes_auditadas) },
        { name = "AUDITOR_AWS_REGIAO_PADRAO", value = var.regiao },
        { name = "AUDITOR_DYNAMODB_TABELA", value = aws_dynamodb_table.achados.name },
      ]

      # Os segredos vêm do SSM por referência (o ECS os injeta como variável); nada na imagem nem aqui
      secrets = [
        for chave, variavel in local.segredos : {
          name      = variavel
          valueFrom = aws_ssm_parameter.segredo[chave].arn
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.auditor.name
          "awslogs-region"        = var.regiao
          "awslogs-stream-prefix" = "auditor"
        }
      }
    }
  ])
}
