output "tabela" {
  value = aws_dynamodb_table.achados.name
}

output "cluster" {
  value = aws_ecs_cluster.auditor.name
}

output "agendamento" {
  value = aws_scheduler_schedule.diario.name
}

output "parametros_ssm_a_preencher" {
  description = "Preencha estes parâmetros (SecureString) fora do Terraform antes de ligar o agendamento."
  value       = [for p in aws_ssm_parameter.segredo : p.name]
}
